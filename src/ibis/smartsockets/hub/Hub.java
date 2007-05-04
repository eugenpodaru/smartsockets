package ibis.smartsockets.hub;


import ibis.smartsockets.SmartSocketsProperties;
import ibis.smartsockets.direct.DirectSocket;
import ibis.smartsockets.direct.DirectSocketAddress;
import ibis.smartsockets.direct.DirectSocketFactory;
import ibis.smartsockets.discovery.Discovery;
import ibis.smartsockets.hub.connections.BaseConnection;
import ibis.smartsockets.hub.connections.HubConnection;
import ibis.smartsockets.hub.connections.VirtualConnections;
import ibis.smartsockets.hub.state.ConnectionsSelector;
import ibis.smartsockets.hub.state.HubDescription;
import ibis.smartsockets.hub.state.HubList;
import ibis.smartsockets.hub.state.StateCounter;
import ibis.smartsockets.util.NetworkUtils;
import ibis.smartsockets.util.TypedProperties;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.apache.log4j.Logger;


public class Hub extends Thread {
    
    private static int GOSSIP_SLEEP = 3000;
    
    private static final int DEFAULT_DISCOVERY_PORT = 24545;
    private static final int DEFAULT_ACCEPT_PORT    = 17878;    

    private static final Logger misclogger;

    private static final Logger goslogger;

    static {
        ibis.util.Log.initLog4J("ibis.smartsockets");
        misclogger = Logger.getLogger("ibis.smartsockets.hub.misc");
        goslogger = Logger.getLogger("ibis.smartsockets.hub.gossip");
    }
    
    private static boolean printStatistics = false;
    private static long STAT_FREQ = 60000;
    
    private final HubList hubs;    
    private final Map<DirectSocketAddress, BaseConnection> connections;
    
    private final Acceptor acceptor;
    private final Connector connector;
            
    private final StateCounter state = new StateCounter();
            
    private final Discovery discovery;
        
    private final VirtualConnections virtualConnections;
    
    private long nextStats;
    
    public Hub(TypedProperties p) throws IOException { 

        super("Hub");
        
        boolean allowDiscovery = 
            p.booleanProperty(SmartSocketsProperties.DISCOVERY_ALLOWED, true);
        
        String [] clusters = 
            p.getStringList(SmartSocketsProperties.HUB_CLUSTERS, ",", null);
        
        if (clusters == null || clusters.length == 0) {
            clusters = new String[] { "*" };
        }
        
        boolean allowSSHForHub = p.booleanProperty(
                SmartSocketsProperties.HUB_SSH_ALLOWED, true);
        
        if (allowSSHForHub) { 
            p.setProperty(SmartSocketsProperties.SSH_IN, "true");
            p.setProperty(SmartSocketsProperties.SSH_OUT, "true");
        }
        
        if (misclogger.isInfoEnabled()) { 
            misclogger.info("Creating Hub for clusters: " 
                    + Arrays.deepToString(clusters));
        }
                
        DirectSocketFactory factory = DirectSocketFactory.getSocketFactory(p);
        
        // Create the hub list
        hubs = new HubList(state);
                
        connections = Collections.synchronizedMap(
                new HashMap<DirectSocketAddress, BaseConnection>());
        
        virtualConnections = new VirtualConnections();
       
        int port = p.getIntProperty(SmartSocketsProperties.HUB_PORT, DEFAULT_ACCEPT_PORT);
        
        boolean delegate = p.booleanProperty(SmartSocketsProperties.HUB_DELEGATE);

        DirectSocketAddress delegationAddress = null;
        
        if (delegate) {         
            String tmp = p.getProperty(SmartSocketsProperties.HUB_DELEGATE_ADDRESS);

            System.err.println("**** HUB USING DELEGATION TO: " + tmp);
            
            try { 
                delegationAddress = DirectSocketAddress.getByAddress(tmp);
            } catch (Exception e) { 
                throw new IOException("Failed to parse delegation address: \""
                        + tmp + "\"");
            }
        }
                
        // NOTE: These are not started until later. We first need to init the
        // rest of the world!        
        acceptor = new Acceptor(p, port, state, connections, hubs, 
                virtualConnections, factory, delegationAddress);        
        
        connector = new Connector(p, state, connections, hubs, 
                virtualConnections, factory);
     
        DirectSocketAddress local = acceptor.getLocal();    
        connector.setLocal(local);
                
        if (goslogger.isInfoEnabled()) {
            goslogger.info("GossipAcceptor listning at " + local);
        }
        
        String name = p.getProperty(SmartSocketsProperties.HUB_SIMPLE_NAME); 
                
        if (name == null || name.length() == 0) { 
            // If the simple name is not set, we try to use the hostname 
            // instead.            
            try { 
                name = NetworkUtils.getHostname();
            }  catch (Exception e) {
                if (misclogger.isInfoEnabled()) {
                    misclogger.info("Failed to find simple name for hub!");
                }
            }
        }        
        
        if (misclogger.isInfoEnabled()) {
            misclogger.info("Hub got name: " + name);
        }
        
        // Create a description for the local machine. 
        HubDescription localDesc = new HubDescription(name, local, state, true);        
        localDesc.setReachable();
        localDesc.setCanReachMe();
        
        hubs.addLocalDescription(localDesc);

        String [] knownHubs = p.getStringList(SmartSocketsProperties.HUB_KNOWN_HUBS);
        
        addHubs(knownHubs);
        
        if (goslogger.isInfoEnabled()) {
            goslogger.info("Starting Gossip connector/acceptor");
        }
                
        acceptor.start();
        connector.start();

        if (misclogger.isInfoEnabled()) {
            misclogger.info("Listning for broadcast on LAN");
        }
                      
        if (allowDiscovery) { 
            String [] suffixes = new String[clusters.length];
        
            // TODO: what does the + do exactly ???
            
            // Check if there is a * in the list of clusters. If so, there is no 
            // point is passing any other values. Note that there may also be a 
            // '+' which means 'any machine -NOT- belonging to a cluster. 
            for (int i=0;i<clusters.length;i++) {             
                if (clusters[i].equals("*") && clusters.length > 0) {
                    suffixes = new String[] { "*" };
                    break;
                } else if (clusters[i].equals("+")) { 
                    suffixes[i] = "+";
                } else { 
                    suffixes[i] = " " + clusters[i];
                }
            }

            int dp = p.getIntProperty(SmartSocketsProperties.DISCOVERY_PORT, 
                    DEFAULT_DISCOVERY_PORT); 

            discovery = new Discovery(dp, 0, 0);         
            discovery.answeringMachine("Any Proxies?", suffixes, local.toString());
   
            misclogger.info("Hub will reply to discovery requests from: " + 
                    Arrays.deepToString(suffixes));
            
        } else {  
            discovery = null;
            misclogger.info("Hub will not reply to discovery requests!");
        } 
        
        if (goslogger.isInfoEnabled()) {
            goslogger.info("Start Gossiping!");
        }

        printStatistics = p.booleanProperty(SmartSocketsProperties.HUB_STATISTICS, false); 
        STAT_FREQ = p.getIntProperty(SmartSocketsProperties.HUB_STATS_INTERVAL, 60000);
        
        nextStats = System.currentTimeMillis() + STAT_FREQ;
        
        start();
    }
    
    public void addHubs(DirectSocketAddress... hubAddresses) { 
        
        DirectSocketAddress local = hubs.getLocalDescription().hubAddress;
        
        if (hubAddresses != null) { 
            for (DirectSocketAddress s : hubAddresses) { 
                if (s != null && !local.sameProcess(s)) { 
                    misclogger.info("Adding hub address: " + s);
                    hubs.add(s);
                } 
            }
        }
    }
        
    public void addHubs(String... hubAddresses) { 
        
        DirectSocketAddress local = hubs.getLocalDescription().hubAddress;
                
        if (hubAddresses != null) { 
            for (String s : hubAddresses) { 
            
                if (s != null) { 
                    try { 
                        DirectSocketAddress tmp = DirectSocketAddress.getByAddress(s);
                    
                        if (!local.sameProcess(tmp)) {
                            misclogger.info("Adding hub address: " + s);
                            hubs.add(tmp);
                        } 
                    } catch (Exception e) { 
                        misclogger.warn("Failed to parse hub address: " + s);
                    }
                }
            }
        }
        
    }
    
    
    private void gossip() { 
        
        if (goslogger.isInfoEnabled()) {
            goslogger.info("Starting gossip round (local state = " 
                    + state.get() + ")");        
            goslogger.info("I know the following hubs:\n" + hubs.toString());
        }
            
        ConnectionsSelector selector = new ConnectionsSelector();
        
        hubs.select(selector);
        
        for (HubConnection c : selector.getResult()) {
            
            if (c != null) {
                c.gossip();
            } else { 
                if (goslogger.isDebugEnabled()) {
                    goslogger.debug("Cannot gossip with " + c
                            + ": NO CONNECTION!");
                }
            }
        }                   
    }
    
    public void delegateAccept(DirectSocket s) {        
        acceptor.addIncoming(s);
    }
    
    public DirectSocketAddress getHubAddress() { 
        return acceptor.getLocal();
    }
    
    private void statistics() { 
        
        if (!printStatistics) { 
            return;
        }
        
        long now = System.currentTimeMillis();
        
        if (now < nextStats) {
            return;
        }
        
        DirectSocketAddress [] cons = connections.keySet().toArray(
                new DirectSocketAddress[0]);
        
        for (DirectSocketAddress s : cons) { 
            
            BaseConnection b = connections.get(s);
            
            if (b != null) { 
               b.printStatistics();
            }
        }
        
        
        nextStats = now + STAT_FREQ; 
    }
        
    public void run() { 
        
        while (true) { 
            try { 
                if (goslogger.isInfoEnabled()) {
                    goslogger.info("Sleeping for " + GOSSIP_SLEEP + " ms.");
                }
                Thread.sleep(GOSSIP_SLEEP);
            } catch (InterruptedException e) {
                // ignore
            }
            
            gossip();
            
            statistics();
        }        
    }     
}