package smartsockets.virtual.modules.splice;

import ibis.util.ThreadPool;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

import smartsockets.direct.DirectSocket;
import smartsockets.direct.DirectSocketFactory;
import smartsockets.direct.IPAddressSet;
import smartsockets.direct.SocketAddressSet;
import smartsockets.hub.ConnectionProtocol;
import smartsockets.util.TypedProperties;
import smartsockets.virtual.ModuleNotSuitableException;
import smartsockets.virtual.VirtualServerSocket;
import smartsockets.virtual.VirtualSocket;
import smartsockets.virtual.VirtualSocketAddress;
import smartsockets.virtual.modules.AbstractDirectModule;

public class Splice extends AbstractDirectModule {
    
    protected static final byte ACCEPT              = 1;
    protected static final byte PORT_NOT_FOUND      = 2;
    protected static final byte WRONG_MACHINE       = 3;    
    protected static final byte CONNECTION_REJECTED = 4;       
    
    private static final int PLEASE_CONNECT  = 1;
    private static final int CONNECT_ACK     = 2;    
    
    private static final byte OK              = 20;
    private static final byte NOT_FOUND  = 21;
    private static final byte NO_EXTERNAL_HUB = 22;
    
    private static final int MAX_ATTEMPTS = 3;
    private static final int DEFAULT_TIMEOUT = 1000;
    private static final int PORT_RANGE = 5;
    
    private boolean behindNAT = false;
    private byte[] behindNATByte = new byte[] { 0 };
    
    private DirectSocketFactory factory;               
    
    private SocketAddressSet myMachine;
    private SocketAddressSet externalHub;

    private LinkedList<SocketAddressSet> hubsToTest = 
        new LinkedList<SocketAddressSet>();    

    private LinkedList<SocketAddressSet> testedHubs = 
        new LinkedList<SocketAddressSet>();    
   
    private HashMap<String, String> hubConnectProperties;
        
    private int nextID = 0;
    
    private final HashMap<Integer, byte [][]> replies = 
        new HashMap<Integer, byte [][]>(); 
    
    public Splice() {
        super("ConnectModule(Splice)", true);
    }
        
    private synchronized int getID() { 
        return nextID++;
    }
    
    private synchronized SocketAddressSet getExternalHub() { 
        return externalHub;
    }
    
    private synchronized void setExternalHub(SocketAddressSet hub) { 
        externalHub = hub;
    }
    
    private synchronized void addFailedHub(SocketAddressSet hub) {
        
        // Note: assumes number of hubs is small!
        if (!testedHubs.contains(hub)) {         
            testedHubs.add(hub);
        }
    }
    
    private synchronized SocketAddressSet getHubToTest() {
        
        // TODO: Remember which hub we have tried already ? 
        if (hubsToTest.size() == 0) {
            
            try { 
                SocketAddressSet [] tmp = serviceLink.hubs();            
                
                if (tmp != null) { 
                    for (SocketAddressSet s : tmp) {                        
                        if (!testedHubs.contains(s)) {                         
                            hubsToTest.add(s);
                        }
                    }
                }
                
            } catch (Exception e) {                
                logger.info("Failed to retrieve hub list!", e);
            }
        }
        
        if (hubsToTest != null && hubsToTest.size() > 0) { 
            return hubsToTest.removeFirst();
        }
            
        return null;
    }
        
    public VirtualSocket connect(VirtualSocketAddress target, int timeout, 
            Map properties) throws ModuleNotSuitableException, IOException {

        // First check if we are trying to connect to ourselves (which makes no 
        // sense for this module... 
        if (target.machine().sameMachine(parent.getLocalHost())) { 
            throw new ModuleNotSuitableException(module + ": Cannot set up " +
                "a connection to myself!"); 
        }
        
        if (logger.isInfoEnabled()) {
            logger.info(module + ": attempting connection setup to " + target);
        }
        
        // Start by extracting the machine address of the target.
        SocketAddressSet targetMachine = target.machine();
        
        // Next we get our own machine address. 
        SocketAddressSet myMachine = parent.getLocalHost();
        
        // We must get an address and port for this machine for the splicing to 
        // be successful. If the machine is NATed this will also produce the 
        // address/port mapping. If the machine is not NATed, it may still 
        // be multihomed and we need to find out which address to use to reach 
        // the outside world....
        SocketAddressSet [] result = new SocketAddressSet[1];
        int [] localPort = new int[1]; 
        
        timeout = getInfo(timeout, result, localPort);

        System.err.println("Got splice info: " + result[0] + " " + localPort[0]);
        
        int id = getID();
        
        // We now inform the target of our intentions, and ask it to cooperate.
        byte [][] message = new byte[5][];
        
        message[0] = fromInt(id);        
        message[1] = fromInt(target.port());        
        message[2] = fromSocketAddressSet(result[0]);
        message[3] = fromInt(timeout);
        message[4] = behindNATByte; 
        
        registerReply(id);
        
        serviceLink.send(targetMachine, target.hub(), module, PLEASE_CONNECT, 
                message);
        
        // We need to wait for a reply here...
        message = getReply(id, timeout);
        
        if (message == null) { 
            if (logger.isInfoEnabled()) {
                logger.info(module + ": Target machine failed to reply to " +
                        "splice request in time!");                    
            }

            throw new ModuleNotSuitableException(module + ": Target machine " +
                    "failed to reply to splice request in time!");
            
        } else if (message[1] == null || message[1].length != 1) {
            
            if (logger.isInfoEnabled()) {
                logger.info(module + ": Target machine failed to produce " +
                        "expected reply to splice request!");
            }
            
            throw new ModuleNotSuitableException(module + ": Target machine " +
                    "failed to produce expected reply to splice request!");
            
        } else if (message[1][0] != OK) {

            if (message[1][0] == NOT_FOUND) {                
                // user error
                throw new SocketException("Target port not found!");
            }
            
            if (logger.isInfoEnabled()) {
                logger.info(module + ": Target machine failed to participate " +
                        "in splicing");
            }
            
            throw new ModuleNotSuitableException(module + ": Target machine " +
                    target + " failed to participate in splicing");
        }
        
        // We got our reply, so get the target range we want to check....
        SocketAddressSet tmp = toSocketAddressSet(message[2]);
        boolean otherBehindNAT = (message[3][0] == 1);
        
        SocketAddressSet [] a = getTargetRange(otherBehindNAT, tmp);
        
        // Try to connect to the target
        DirectSocket s = connect(a, localPort[0], DEFAULT_TIMEOUT, target.port());
                
        if (s == null) { 
            throw new ModuleNotSuitableException(module + ": Failed to connect "
                    + " to " + target);                         
        }

        // If the connection was succesfull, we hand over the socket to the 
        // parent for handshakes, checks, etc.
        return handleConnect(target, s, timeout, properties);
    }

    private SocketAddressSet [] getTargetRange(boolean behindNAT,  
            SocketAddressSet realTarget) throws UnknownHostException { 
        
        SocketAddressSet [] a = null;
        
        if (!behindNAT) { 
            // the machine is likely to be behind a firewall, so no port range 
            // prediction is necessary
            a = new SocketAddressSet[1];
            a[0] = realTarget;
        } else { 
            // The machine is behind a NAT, so use port range prediction...
            a = new SocketAddressSet[PORT_RANGE];
            a[0] = realTarget;
            
            int port = realTarget.getPorts(false)[0];
            IPAddressSet ads = realTarget.getAddressSet();
            
            for (int i=1;i<PORT_RANGE;i++) { 
                a[i] = SocketAddressSet.getByAddress(ads, port+1);
            }
        }

        return a;
    }
    
    private int getLocalPort(int timeout) throws SocketTimeoutException {
        
        long deadline = System.currentTimeMillis() + timeout;
        
        int port = 0;
        
        while (port == 0) {
            try { 
                port = factory.getAvailablePort();
            } catch (Exception e) {
                try { 
                    Thread.sleep(100);
                } catch (Exception e2) {
                    // ignore
                }
                
                if (System.currentTimeMillis() > deadline) { 
                    throw new SocketTimeoutException("Failed to get port " +
                            "number within timeout.");
                }                
            }            
        }
        
        return port;        
    }

    private synchronized void registerReply(Integer id) {     
        replies.put(id, null);        
    }
    
    private synchronized byte [][] getReply(Integer id, int timeout) { 
        
        //System.out.println("Waiting for ACK");
        
        byte [][] message = replies.get(id);
        
        long deadline = System.currentTimeMillis() + timeout;
        long timeleft = timeout;
                
        while (message == null && timeleft > 0) { 
            try { 
                wait(timeleft);                
            } catch (Exception e) {
                // ignore
            }
            
            message = replies.get(id);
            
            if (message == null) { 
                timeleft = deadline - System.currentTimeMillis();
            }
        }
        
        replies.remove(id);        
        
        // System.out.println("Got ACK ? " + (message != null));
        
        return message;
    }

    private synchronized void storeReply(Integer id, byte [][] message) { 
       
        if (replies.containsKey(id)) { 
            replies.put(id, message);
            notifyAll();
        } else { 
            if (logger.isInfoEnabled()) {
                logger.info(module + ": ACK dropped, no one is listning!");
            }
        }
    }

    
    private int getInfo(int timeout, SocketAddressSet [] result, 
            int [] localPort) throws IOException, ModuleNotSuitableException {
        
        int local = -1;        

        long deadline = 0;
        long timeleft = timeout;
        
        if (timeleft > 0) { 
            deadline = System.currentTimeMillis() + timeout;
        }
                
        if (myMachine.numberOfAddresses() == 1 && myMachine.hasGlobalAddress()) { 
            // nothing to map, so the result only depends on the local address
            localPort[0] = getLocalPort(timeout); 
            result[0] = SocketAddressSet.getByAddress(myMachine.getAddressSet(), 
                    localPort[0]);
            
            if (deadline == 0) { 
                return 0;
            } else {             
                return (int) (deadline - System.currentTimeMillis());
            }
        }
        
        while (true) { 

            boolean testing = false;
            SocketAddressSet hub = getExternalHub();

            if (hub == null) { 
                // No suitable external hub is known yet!
                testing = true;
                hub = getHubToTest();
        
                if (deadline > 0) {             
                    timeleft = deadline - System.currentTimeMillis();
                }
        
                // Failed to get list of hubs!
                if (hub == null) {
                    throw new ModuleNotSuitableException("Failed to find external hub");            
                }
            }
                
            try { 
                local = getInfo(hub, (int) timeleft, local, result);
            } catch (IOException e) {
                // Failed to contact hub!
                logger.info("Failed to contact hub: " + hub.toString() 
                        + " for splice info (will try other hubs)", e);
            }            
                
            if (result[0] != null) {
                // Success!
                localPort[0] = local;
                
                if (testing) {
                    // We found a suitable hub, so save it!
                    setExternalHub(hub);
                }
                
                if (deadline > 0) {             
                    return (int) (deadline - System.currentTimeMillis());                                       
                } else { 
                    return 0;
                }                        
            }  

            if (testing) {
                // This hub was just a test, so try the next one...
                addFailedHub(hub);
            } else { 
                // We already had a decent hub, but it disappeared!
                setExternalHub(null);
            } 
                
            // Check deadline
            if (deadline > 0) {
                timeleft = deadline - System.currentTimeMillis();

                if (timeleft <= 0) { 
                    throw new SocketTimeoutException("Timeout while trying" 
                            + " to find external hub");
                }
            }            
        }
    }
    
    private int getInfo(SocketAddressSet externalHub, int timeout, int local, 
            SocketAddressSet [] result) throws IOException { 

        DirectSocket s = null;
        OutputStream out = null;
        DataInputStream in = null;
        
        if (hubConnectProperties == null) { 
            hubConnectProperties = new HashMap<String, String>();
            hubConnectProperties.put("direct.forcePublic", null);
        }
        
        try { 
            s = factory.createSocket(externalHub, timeout, local, -1, -1, 
                    hubConnectProperties, false, 0);
            
            s.setReuseAddress(true); // TODO: is this correct ?                       
            s.setSoTimeout(timeout);
            
            local = s.getLocalPort();
            
            out = s.getOutputStream();
            out.write(ConnectionProtocol.GET_SPLICE_INFO);
            out.flush();
            
            in = new DataInputStream(s.getInputStream());            
            String addr = in.readUTF();
            int port = in.readInt();
            
            SocketAddressSet tmp = SocketAddressSet.getByAddress(addr, port);
            
            if (!tmp.hasGlobalAddress()) { 
                // We didn't get an external address! So we don't return any 
                // mapping, just the local port (so it can be reused).
                return local;                   
            }
            
            result[0] = tmp;
            
            for (int i=1;i<result.length;i++) {
                result[i] = SocketAddressSet.getByAddress(addr, port+i);
            }
            
            return local;        
        } finally { 
            DirectSocketFactory.close(s, out, in);
        }
    }
    
    private DirectSocket connect(SocketAddressSet [] target, int localPort, 
            int timeout, int userdata) throws IOException, ModuleNotSuitableException {
        
        // TODO: This will give you very long waiting time before the setup 
        // fails! Better to fail fast and retry the entire setup ? Or maybe 
        // change the timeout to something small ? 
        
        // .println("Connect " + Arrays.deepToString(target));            
        
        long deadline = 0;
        long timeleft = timeout;
        
        if (timeout > 0) { 
            deadline = System.currentTimeMillis() + timeout;
        }
                
        if (target.length == 1) { 
            logger.debug(module + ": Single splice attempt!");
        
            try { 
                return factory.createSocket(target[0], 5000, localPort, -1, -1,
                        null, true, userdata);
            } catch (IOException e) {
                logger.info(module + ": Connection failed " 
                        + target.toString(), e);
                
                
              //  System.out.println(module + ": Connection failed " 
              //          + target.toString() + " ");
               // e.printStackTrace();
            }   
            
        } else { 
            for (int i=0;i<MAX_ATTEMPTS;i++) {
                for (int t=0;t<target.length;t++) {             
    
                    logger.debug(module + ": Splice attempt (" + i + "/" + t + ")");

                    try { 
                        return factory.createSocket(target[t], timeout, 
                                localPort, -1, -1, null, false, userdata);
                    } catch (IOException e) {
                        logger.info(module + ": Connection failed " 
                                + target.toString(), e);
                        
                        //System.out.println(module + ": Connection2 failed " 
                        //        + target.toString() + " " + e);
                    }           
                }    
            }
        }
        
        
        logger.debug(module + ": Splice failed.");
        
        return null;
    }
    
    public SocketAddressSet getAddresses() {
        // Nothing to do here, since we don't extend the address in any way... 
        return null;
    }

    public void initModule(TypedProperties properties) throws Exception {
        // Create a direct socket factory.
        factory = DirectSocketFactory.getSocketFactory();     
    }

    public boolean matchAdditionalRuntimeRequirements(Map requirements) {
        // Alway match ? 
        return true;
    }

    public void startModule() throws Exception {
        
        if (serviceLink == null) {
            throw new Exception(module + ": no service link available!");       
        }
        
        behindNAT = !parent.getLocalHost().hasGlobalAddress();   
        behindNATByte[0] = (byte) (behindNAT ? 1 : 0);
        
        myMachine = parent.getLocalHost();
    }
   
    private void handleConnect(SocketAddressSet src, SocketAddressSet srcHub, 
            byte [][] message) { 

        // Try to extract the necessary info from the message
        //System.out.println("Got splice request");
        
        // Next, check if the message has enough parts
        if (message == null || message.length != 5) { 
            logger.warn(module + ": malformed connect message " + src + "@" 
                    + srcHub + "\"" +  Arrays.deepToString(message) + "\"");
            return;
        }
        
        SpliceRequest r = new SpliceRequest();   
        
        r.id = message[0];        
        r.src = src;
        r.srcHub = srcHub;
        
        try {
            r.port = toInt(message[1]);
            r.target = toSocketAddressSet(message[2]);
            r.timeout = toInt(message[3]);
            r.otherBehindNAT = (message[4][0] == 1);
        } catch (Exception e) {
            logger.warn(module + ": failed to parse connect message " + src 
                    + "@" + srcHub + "\"" +  message + "\"", e);
            return;
        }

       // System.out.println("Splice request: " + r.otherBehindNAT);
        
        ThreadPool.createNew(r, "Splice Request Handler");
 
    }
    
    private void handleReply(SocketAddressSet src, SocketAddressSet srcHub, 
            byte [][] message) { 
        
      //  System.out.println("Got splice reply");
        
        // Next, check if the message has enough parts
        if (message == null || !(message.length == 2 || message.length == 4)) { 
            logger.warn(module + ": malformed connect ack " + src + "@" 
                    + srcHub + "\"" +  Arrays.deepToString(message) + "\"");
            return;
        }
        
        storeReply(new Integer(toInt(message[0])), message);
    }
    
    public void gotMessage(SocketAddressSet src, SocketAddressSet srcHub, 
            int opcode, byte [][] message) {

        if (logger.isInfoEnabled()) {
            logger.info(module + ": got message " + src + "@" + srcHub + " " 
                    + opcode + " \"" +  message + "\"");
        }
                       
        switch (opcode) { 
        case PLEASE_CONNECT:
            handleConnect(src, srcHub, message);            
            break;
            
        case CONNECT_ACK:            
            handleReply(src, srcHub, message);
            break;
            
        default:
            logger.warn(module + ": ignoring message " + src + "@" + srcHub 
                    + " " + opcode + "\"" +  message + "\"");
        }
    }

    protected VirtualSocket createVirtualSocket(VirtualSocketAddress a, 
            DirectSocket s, DataOutputStream out, DataInputStream in) {     
        return new SplicedVirtualSocket(a, s, out, in, null);
    }
        
    private class SpliceRequest implements Runnable {
        
        byte [] id; 
        
        SocketAddressSet src; 
        SocketAddressSet srcHub; 
        SocketAddressSet target;
        
        int port = 0;    
        int timeout = 0;
        
        boolean otherBehindNAT = false;

        public void run() {
            
            // Check if we can find the port that the sender is interested in
            VirtualServerSocket ss = parent.getServerSocket(port);
            
            if (ss == null) {
                if (logger.isInfoEnabled()) {
                    logger.info(module + ": port " + port + " not found!");
                }
                
                // Port not found... send reply
                serviceLink.send(src, srcHub, module, CONNECT_ACK, new byte[][] { 
                        id, new byte[] { NOT_FOUND }});            
                return;            
            }
            
            SocketAddressSet [] result = new SocketAddressSet[1]; 
            int [] localPort = new int[1];
            
            try {         
                timeout = getInfo(timeout, result, localPort);
            } catch (Exception e) {
                // ignore
            }
            
            if (result[0] == null) { 
                // timeout or exception....
            //    System.out.println("Sending NACK");
                
                serviceLink.send(src, srcHub, module, CONNECT_ACK, new byte[][] { 
                        id, new byte[] { NO_EXTERNAL_HUB }});            
                return;                        
            }
            
        //    System.out.println("Sending ACK " + result[0] + " " + behindNATByte);
            
            // Send reply
            serviceLink.send(src, srcHub, module, CONNECT_ACK, new byte[][] { 
                    id, new byte[] { OK }, fromSocketAddressSet(result[0]), 
                    behindNATByte});            
            
            // Setup connection     
            try {            
                SocketAddressSet [] a = getTargetRange(otherBehindNAT, target);
            
                DirectSocket s = connect(a, localPort[0], timeout, 0);
                
                if (s == null) {
                    if (logger.isInfoEnabled()) {
                        logger.info(module + ": Incoming connection setup failed!");
                    }
                    return;
                }
                   
                handleAccept(s);
                            
            } catch (Exception e) {
                logger.info(module + ": Incoming connection setup failed!", e);            
            }
        }    
    }
    
}
