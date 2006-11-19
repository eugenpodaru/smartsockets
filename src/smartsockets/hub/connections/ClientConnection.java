package smartsockets.hub.connections;


import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

import org.apache.log4j.Logger;

import smartsockets.direct.DirectSocket;
import smartsockets.direct.DirectSocketFactory;
import smartsockets.direct.SocketAddressSet;
import smartsockets.hub.servicelink.ServiceLinkProtocol;
import smartsockets.hub.state.AddressAsStringSelector;
import smartsockets.hub.state.ClientsByTagAsStringSelector;
import smartsockets.hub.state.DetailsSelector;
import smartsockets.hub.state.DirectionsAsStringSelector;
import smartsockets.hub.state.HubDescription;
import smartsockets.hub.state.HubList;

public class ClientConnection extends MessageForwardingConnection {

    private static Logger conlogger = 
        ibis.util.GetLogger.getLogger("smartsockets.hub.connections.client"); 
    
    private static Logger reqlogger = 
        ibis.util.GetLogger.getLogger("smartsockets.hub.request"); 
    
    private static Logger reglogger = 
        ibis.util.GetLogger.getLogger("smartsockets.hub.registration"); 
    
    private final SocketAddressSet clientAddress;
           
    public ClientConnection(SocketAddressSet clientAddress, DirectSocket s, 
            DataInputStream in, DataOutputStream out, 
            Map<SocketAddressSet, BaseConnection> connections,
            HubList hubs) {
     
        super(s, in, out, connections, hubs);        
        this.clientAddress = clientAddress;
        
        if (conlogger.isDebugEnabled()) {
            conlogger.debug("Created client connection: " + clientAddress);
        }
    }
  
    protected String incomingVirtualConnection(VirtualConnection origin, 
            MessageForwardingConnection m, SocketAddressSet source, 
            SocketAddressSet target, String info, int timeout) {
       
        // Incoming request for a virtual connection to the client connected to 
        // this ClientConnection....
        String id = getID();

        // Register the fact that we expect a reply for this id.
        replyPending(id);
        
        // Send the connect request to the client
        try { 
            synchronized (this) {
                out.write(ServiceLinkProtocol.CREATE_VIRTUAL);           
                out.writeUTF(id);            
                out.writeUTF(source.toString());            
                out.writeUTF(info);            
                out.writeInt(timeout);            
                out.flush();
            }
        } catch (Exception e) {
            conlogger.warn("Connection to " + clientAddress + " is broken!", e);
            handleDisconnect();
            return "Lost connection to target";
        }
            
        // Wait for the reply. This will remove the id before it returns, even 
        // if no reply was received (this is needed to discover that the client 
        // has already left before the server has replied). 
        String [] reply = waitForReply(id, timeout);
        
        if (reply == null) {
            // receiver took too long to accept: timeout
            return "Timeout";
        }
        
        if (reply.length != 2) {
            // got malformed reply
            return "Received malformed reply";
        }
        
        if (reply[0].equals("DENIED")) { 
            // connection refused
            return reply[1];
        }

        if (!reply[0].equals("OK")) {
            // should be DENIED or OK, so we got a malformed reply
            return "Received malformed reply";
        }

        int newIndex = 0;
        
        try { 
            newIndex = Integer.parseInt(reply[1]);
        } catch (Exception e) {
            // got malformed reply
            return "Received malformed reply";
        }
            
        conlogger.warn("Got new connection: " + newIndex);
        
        // if the client accepts, get the virtual connection on this end...       
        VirtualConnection v = vcs.newVC(newIndex);
        
        // And tie the two together!
        vclogger.warn("CLIENT Connection setup of: " + origin.index 
                + "<-->" + v.index);
        
        v.init(m, origin.index, 0);
        origin.init(this, v.index, 0);
        
        // return null to indicate the lack of errors ;-)
        return null;
    }
    
    protected void forwardVirtualClose(int index) { 

        vclogger.warn("CLIENT Sending closing connection: " + index);
                
        // forward the close
        try {
            synchronized (this) {
                out.write(ServiceLinkProtocol.CLOSE_VIRTUAL);           
                out.writeInt(index);            
                out.flush();
            }
        } catch (Exception e) {
            conlogger.warn("Connection to " + clientAddress + " is broken!", e);
            handleDisconnect();
        }
    }
    
    protected void forwardVirtualMessage(int index, byte [] data) {
        
        // forward the message
        try {
            synchronized (this) {
                out.write(ServiceLinkProtocol.MESSAGE_VIRTUAL);           
                out.writeInt(index);
                out.writeInt(data.length);
                out.write(data);
                out.flush();                
            }
        } catch (Exception e) {
            conlogger.warn("Connection to " + clientAddress + " is broken!", e);
            handleDisconnect();
        }        
    }
    
    protected void forwardVirtualMessageAck(int index) { 

        // forward the message
        try {
            synchronized (this) {
                out.write(ServiceLinkProtocol.MESSAGE_VIRTUAL_ACK);           
                out.writeInt(index);
                out.flush();                
            }
        } catch (Exception e) {
            conlogger.warn("Connection to " + clientAddress + " is broken!", e);
            handleDisconnect();
        }        
    }
        
    private void handleMessage() throws IOException { 
        // Read the message
        
        ClientMessage cm = new ClientMessage(clientAddress, 
                knownHubs.getLocalDescription().hubAddress, 0, in);
        
        if (meslogger.isDebugEnabled()) {
            meslogger.debug("Incoming message: " + cm);
        }
        
        forward(cm, true);
    } 
                
    private void handleDisconnect() {
        
        if (knownHubs.getLocalDescription().removeClient(clientAddress)) {
            if (conlogger.isDebugEnabled()) {
                conlogger.debug("Removed client connection " + clientAddress);
            }
        } else if (conlogger.isDebugEnabled()) {
                conlogger.debug("Failed to removed client connection " 
                        + clientAddress + "!");
        }
        
        connections.remove(clientAddress);
        DirectSocketFactory.close(s, out, in);            
    } 
    
    protected synchronized boolean sendMessage(ClientMessage m) {  
        
        try { 
            out.write(ServiceLinkProtocol.MESSAGE);            
            m.writePartially(out);
            out.flush();
            return true;
        } catch (IOException e) {            
            meslogger.warn("Connection " + clientAddress + " is broken!", e);
            DirectSocketFactory.close(s, out, in);
            return false;                
        }
    }
        
    private void handleListHubs() throws IOException { 
        
        String id = in.readUTF();
        
        if (reqlogger.isDebugEnabled()) {
            reqlogger.debug("Connection " + clientAddress + " return id: " + id);
        }
                
        AddressAsStringSelector as = new AddressAsStringSelector();
        
        knownHubs.select(as);
                
        LinkedList<String> result = as.getResult();
        
        synchronized (this) {
            out.write(ServiceLinkProtocol.INFO);           
            out.writeUTF(id);            
            out.writeInt(result.size());
        
            for (String s : result) {  
                out.writeUTF(s);
            } 
            
            out.flush();
        }
    } 

    private void handleListHubDetails() throws IOException { 
        
        String id = in.readUTF();
        
        if (reqlogger.isDebugEnabled()) {
            reqlogger.debug("Connection " + clientAddress + " return id: " + id);
        }
        
        DetailsSelector as = new DetailsSelector();
        
        knownHubs.select(as);
        
        LinkedList<String> result = as.getResult();
        
        synchronized (this) {
            out.write(ServiceLinkProtocol.INFO);           
            out.writeUTF(id);            
            out.writeInt(result.size());
            
            if (reqlogger.isDebugEnabled()) {
                reqlogger.debug("Connection " + clientAddress + " result: " 
                        + result.size() + " " + result);
            }
        
            for (String s : result) { 
                out.writeUTF(s);
            } 
            
            out.flush();
        }
    } 

    
    private void handleListClientsForHub() throws IOException { 
        String id = in.readUTF();
        String hub = in.readUTF();
        String tag = in.readUTF();
        
        if (reqlogger.isDebugEnabled()) {
            reqlogger.debug("Connection " + clientAddress + " return id: " + id); 
        }
        
        LinkedList<String> result = new LinkedList<String>();
        
        try { 
            HubDescription d = knownHubs.get(new SocketAddressSet(hub));            
            d.getClientsAsString(result, tag);            
        } catch (UnknownHostException e) {
            reqlogger.warn("Connection " + clientAddress + " got illegal hub " 
                    + "address: " + hub); 
        }
      
        synchronized (this) {
            out.write(ServiceLinkProtocol.INFO);           
            out.writeUTF(id);            
            out.writeInt(result.size());

            for (String s : result) { 
                out.writeUTF(s);
            } 
            
            out.flush();
        }
    } 

    private void handleListClients() throws IOException { 
        
        String id = in.readUTF();
        String tag = in.readUTF();
        
        if (reqlogger.isDebugEnabled()) {
            reqlogger.debug("Connection " + clientAddress + " return id: " + id);
        }
        
        ClientsByTagAsStringSelector css = new ClientsByTagAsStringSelector(tag);
        
        knownHubs.select(css);
        
        LinkedList<String> result = css.getResult();

        synchronized (this) {
            out.write(ServiceLinkProtocol.INFO);           
            out.writeUTF(id);            
            out.writeInt(result.size());

            if (reqlogger.isDebugEnabled()) {
                reqlogger.debug("Connection " + clientAddress + " returning : " 
                        + result.size() + " clients: " + result);
            }
        
            for (String s : result) {
                out.writeUTF(s);
            } 
            
            out.flush();
        }
    } 

    private void handleGetDirectionsToClient() throws IOException { 
        String id = in.readUTF();
        String client = in.readUTF();
         
        if (reqlogger.isDebugEnabled()) {
            reqlogger.debug("Connection " + clientAddress + " return id: " + id);
        }
        
        DirectionsAsStringSelector ds = 
            new DirectionsAsStringSelector(new SocketAddressSet(client));
        
        knownHubs.select(ds);
        
        LinkedList<String> result = ds.getResult();
        
        synchronized (this) {
            out.write(ServiceLinkProtocol.INFO);           
            out.writeUTF(id);            
            out.writeInt(result.size());

            if (reqlogger.isDebugEnabled()) {
                reqlogger.debug("Connection " + clientAddress + " returning : " 
                        + result.size() + " possible directions: " + result);
            }
        
            for (String tmp : result) { 
                out.writeUTF(tmp);
            } 
            
            out.flush();
        }
    } 
    
    private void registerInfo() throws IOException { 
        
        String id = in.readUTF();
        String tag = in.readUTF();
        String info = in.readUTF();

        if (reqlogger.isDebugEnabled()) {
            reglogger.debug("Connection " + clientAddress + " return id: " + id +  
                    " adding info: " + tag + " " + info);
        }
               
        HubDescription localHub = knownHubs.getLocalDescription();
        
        synchronized (this) {
            out.write(ServiceLinkProtocol.INFO);           
            out.writeUTF(id);            
            out.writeInt(1);
        
            if (localHub.addService(clientAddress, tag, info)) { 
                out.writeUTF("OK");
            } else { 
                out.writeUTF("DENIED");
            }
            
            out.flush();
        }
    } 

    private void updateInfo() throws IOException { 
        
        String id = in.readUTF();
        String tag = in.readUTF();
        String info = in.readUTF();

        if (reqlogger.isDebugEnabled()) {
            reglogger.debug("Connection " + clientAddress + " return id: " + id +  
                    " updating info: " + tag + " " + info);         
        }
        
        HubDescription localHub = knownHubs.getLocalDescription();
        
        synchronized (this) {
            out.write(ServiceLinkProtocol.INFO);           
            out.writeUTF(id);            
            out.writeInt(1);
        
            if (localHub.updateService(clientAddress, tag, info)) { 
                out.writeUTF("OK");
            } else { 
                out.writeUTF("DENIED");
            }
            
            out.flush();
        }
    } 

    private void handleRemoveProperty() throws IOException { 
        
        String id = in.readUTF();
        String tag = in.readUTF();
        
        if (reqlogger.isDebugEnabled()) {
            reglogger.debug("Connection " + clientAddress + " return id: " + id +  
                    " removing info: " + tag);
        }
               
        HubDescription localHub = knownHubs.getLocalDescription();
        
        synchronized (this) {
            out.write(ServiceLinkProtocol.INFO);           
            out.writeUTF(id);            
            out.writeInt(1);
        
            if (localHub.removeService(clientAddress, tag)) { 
                out.writeUTF("OK");
            } else { 
                out.writeUTF("DENIED");
            }
            
            out.flush();
        }
    } 
           
    private void handleCreateVirtualConnection() throws IOException { 
        
        String id = in.readUTF();
        
        int index = in.readInt();
        int timeout = in.readInt();
        
        String target = in.readUTF();
        String info = in.readUTF();        
        
        if (vclogger.isDebugEnabled()) {
            vclogger.debug("Connection " + clientAddress 
                    + " return id: " + id + " creating virtual connection to " 
                    + target);
        }
        
        String result = createVirtualConnection(index, clientAddress, 
                new SocketAddressSet(target), info, timeout);
    
        synchronized (this) {
            out.write(ServiceLinkProtocol.INFO);           
            out.writeUTF(id);            
            out.writeInt(2);
        
            if (result == null) { 
                out.writeUTF("OK");
                out.writeUTF(Integer.toString(DEFAULT_CREDITS));
            } else { 
                out.writeUTF("DENIED");
                out.writeUTF(result);
            }
            
            out.flush();
        }         
    } 

    private void handleAckCreateVirtualConnection() throws IOException { 
        
        String localID = in.readUTF();        
        String result = in.readUTF();
        
        if (vclogger.isDebugEnabled()) {
            vclogger.debug("Got reply to VC: " + localID + " " + result);
        }
        
        if (result.equals("DENIED")) { 
            String error = in.readUTF();           
            storeReply(localID, new String [] { result, error });
            return;
        }
        
        int index = in.readInt();
        
        if (vclogger.isDebugEnabled()) {
            vclogger.debug("read index: " + index);
        }
        
        String remoteID = in.readUTF();
        
        boolean b = storeReply(localID, 
                new String [] { result, Integer.toString(index) });
        
        synchronized (this) {
            out.write(ServiceLinkProtocol.INFO);           
            out.writeUTF(remoteID);            
            out.writeInt(1);            
            out.writeUTF(b ? "OK" : "DENIED");            
            out.flush();
        }         
    } 

    
    private void handleCloseVirtualConnection() throws IOException { 

        String id = in.readUTF();        
        int vc = in.readInt();
        
        if (reqlogger.isDebugEnabled()) {
            reglogger.debug("Connection " + clientAddress + " return id: " + id +  
                    " closing vitual connection " + vc);
        }
               
        vclogger.warn("CLIENT got request to close connection: " + vc, 
                new Exception());
                
        closeVirtualConnection(vc);
        
        synchronized (this) {
            out.write(ServiceLinkProtocol.INFO);           
            out.writeUTF(id);
            
          //  if (result == null) { 
                out.writeInt(1);            
                out.writeUTF("OK");
          //  } else { 
          //      out.writeInt(2);            
           //     out.writeUTF("DENIED");
           //     out.writeUTF(result);
           // }
            
            out.flush();
        }         
    } 
    
    private void handleForwardVirtualMessage() throws IOException {
    
        int vc = in.readInt();
        int size = in.readInt();
        
        // TODO: optimize!
        byte [] data = new byte[size];        
        in.readFully(data);
                      
        forwardMessage(vc, data);
    }
    
    private void handleForwardVirtualMessageAck() throws IOException {
        
        int vc = in.readInt();
        forwardMessageAck(vc);
    }
        
    protected String getName() {
        return "ClientConnection(" + clientAddress + ")";
    }

    protected boolean runConnection() {           
                     
        try { 
            int opcode = in.read();

            switch (opcode) { 
            case ServiceLinkProtocol.MESSAGE:
                if (meslogger.isDebugEnabled()) {
                    meslogger.debug("Connection " + clientAddress + " got message");
                }                     
                handleMessage();
                return true;
                
            case ServiceLinkProtocol.DISCONNECT:
                if (conlogger.isDebugEnabled()) {
                    conlogger.debug("Connection " + clientAddress + " disconnecting");
                } 
                handleDisconnect();
                return false;
            
            case ServiceLinkProtocol.HUBS:
                if (reqlogger.isDebugEnabled()) {
                    reqlogger.debug("Connection " + clientAddress + " requests " 
                            + "hubs");
                } 
                handleListHubs();
                return true;

            case ServiceLinkProtocol.HUB_DETAILS:
                if (reqlogger.isDebugEnabled()) {
                    reqlogger.debug("Connection " + clientAddress + " requests " 
                            + "hub details");
                } 
                handleListHubDetails();
                return true;
                
            case ServiceLinkProtocol.CLIENTS_FOR_HUB:
                if (reqlogger.isDebugEnabled()) {
                    reqlogger.debug("Connection " + clientAddress + " requests" 
                            + " local clients");
                } 
                handleListClientsForHub();
                return true;
            
            case ServiceLinkProtocol.ALL_CLIENTS:
                if (reqlogger.isDebugEnabled()) {
                    reqlogger.debug("Connection " + clientAddress + " requests" 
                            + " all clients");
                }
                handleListClients();
                return true;
            
            case ServiceLinkProtocol.DIRECTION:
                if (reqlogger.isDebugEnabled()) {
                    reqlogger.debug("Connection " + clientAddress + " requests" 
                            + " direction to other client");
                }
                handleGetDirectionsToClient();
                return true;
            
            case ServiceLinkProtocol.REGISTER_PROPERTY:
                if (reglogger.isDebugEnabled()) {
                    reglogger.debug("Connection " + clientAddress + " requests" 
                            + " info registration");
                }
                registerInfo();
                return true;
            
            case ServiceLinkProtocol.UPDATE_PROPERTY:
                if (reglogger.isDebugEnabled()) {
                    reglogger.debug("Connection " + clientAddress + " requests" 
                            + " info update");
                }
                updateInfo();
                return true;
            
            case ServiceLinkProtocol.REMOVE_PROPERTY:
                if (reglogger.isDebugEnabled()) {
                    reglogger.debug("Connection " + clientAddress + " requests" 
                            + " info removal");
                }
                handleRemoveProperty();
                return true;
            
            case ServiceLinkProtocol.CREATE_VIRTUAL:
                if (reglogger.isDebugEnabled()) {
                    reglogger.debug("Connection " + clientAddress + " requests" 
                            + " setup of virtual connection");
                }
                handleCreateVirtualConnection();
                return true;
          
            case ServiceLinkProtocol.CREATE_VIRTUAL_ACK:
                if (reglogger.isDebugEnabled()) {
                    reglogger.debug("Connection " + clientAddress + " replied" 
                            + " to virtual connection setup");
                }
                handleAckCreateVirtualConnection();                
                return true;                        
                          
            case ServiceLinkProtocol.CLOSE_VIRTUAL:
                if (reglogger.isDebugEnabled()) {
                    reglogger.debug("Connection " + clientAddress + " requests" 
                            + " close of virtual connection");
                }
                handleCloseVirtualConnection();
                return true;            
            
            case ServiceLinkProtocol.MESSAGE_VIRTUAL:
                if (reglogger.isDebugEnabled()) {
                    reglogger.debug("Connection " + clientAddress + " sends" 
                            + " message over virtual connection");
                }
                handleForwardVirtualMessage();
                return true;                        
            
            case ServiceLinkProtocol.MESSAGE_VIRTUAL_ACK:
                if (reglogger.isDebugEnabled()) {
                    reglogger.debug("Connection " + clientAddress + " sends" 
                            + " ack over virtual connection");
                }               
                handleForwardVirtualMessageAck();
                return true;                        
            
                
            default:
                conlogger.warn("Connection " + clientAddress 
                        + " got unknown " + "opcode " + opcode 
                        + " -- disconnecting");
                handleDisconnect();
                return false;                
            } 
            
        } catch (Exception e) { 
            conlogger.warn("Connection to " + clientAddress + " is broken!", e);
            handleDisconnect();
        }
        
        return false;
    }

   
}
