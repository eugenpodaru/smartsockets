package ibis.smartsockets.hub;

import ibis.smartsockets.direct.DirectSocketAddress;
import ibis.smartsockets.direct.DirectSocketFactory;
import ibis.smartsockets.hub.connections.VirtualConnections;
import ibis.smartsockets.hub.state.HubList;
import ibis.smartsockets.hub.state.StateCounter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


abstract class CommunicationThread implements Runnable {

    protected static final int DEFAULT_TIMEOUT = 10000;

    protected final String name;
    protected final StateCounter state;

    protected static final Logger hublogger =
        LoggerFactory.getLogger("ibis.smartsockets.hub");

    protected final Connections connections;

    protected final HubList knownHubs;
    protected final VirtualConnections virtualConnections;
    protected final DirectSocketFactory factory;

    protected DirectSocketAddress local;
    protected String localAsString;
    protected Thread thread;

    private boolean end = false;

    protected CommunicationThread(String name, StateCounter state,
            Connections connections, HubList knownHubs, VirtualConnections vcs,
            DirectSocketFactory factory) {

        this.name = name;
        this.state = state;
        this.connections = connections;
        this.knownHubs = knownHubs;
        this.virtualConnections = vcs;
        this.factory = factory;
    }

    protected void setLocal(DirectSocketAddress local) {
        this.local = local;
        this.localAsString = local.toString();
    }

    protected DirectSocketAddress getLocal() {
        return local;
    }

    protected String getLocalAsString() {
        return localAsString;
    }

    protected synchronized boolean getDone() {
        return end;
    }

    public synchronized void end() {
        end = true;

        try {
            thread.interrupt();
        } catch (Exception e) {
            // ignore ?
        }
    }

    public void activate() {
        // thread = ThreadPool.createNew(this, name);
        thread = new Thread(this, name);
        thread.setDaemon(true);
        thread.start();
    }
}
