package test.direct.splice;


import ibis.smartsockets.direct.DirectServerSocket;
import ibis.smartsockets.direct.DirectSimpleSocket;
import ibis.smartsockets.direct.DirectSocketAddress;
import ibis.smartsockets.direct.DirectSocketFactory;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;


public class ThreePointSpliceTest {

    private static final int LOCAL_PORT = 16889;

    private static DirectSocketFactory sf =
        DirectSocketFactory.getSocketFactory();

    private static void client(String key, DirectSocketAddress server)
        throws IOException {

        Map<String, Object> properties = new HashMap<String, Object>();
        properties.put("allowSSH", "false");

        DirectSimpleSocket s = (DirectSimpleSocket) sf.createSocket(server, 0,
                LOCAL_PORT, properties);

        System.out.println("Created connection to " + server +
                " on local address " + s.getLocalSocketAddress()
                + " remote address " + s.getRemoteSocketAddress());

        DataInputStream in = new DataInputStream(s.getInputStream());
        DataOutputStream out = new DataOutputStream(s.getOutputStream());

        out.writeUTF(key);
        out.flush();

        String addr = in.readUTF();
        int port = in.readInt();

        System.out.println("Server gives me " + addr + " " + port);


        DirectSocketAddress [] target = new DirectSocketAddress[5];

        for (int i=0;i<target.length;i++) {
            target[i] = DirectSocketAddress.getByAddress(addr, port+i);
        }

        for (int i=0;i<30;i++) {

            for (int t=0;t<target.length;t++) {
                try {
                    s = (DirectSimpleSocket) sf.createSocket(target[i], 0,
                            LOCAL_PORT, properties);

                    System.out.println("Created connection to " + target[i] +
                            " on local address " + s.getLocalSocketAddress()
                            + " remote address " + s.getRemoteSocketAddress());

                    in = new DataInputStream(s.getInputStream());
                    out = new DataOutputStream(s.getOutputStream());

                    out.writeUTF("Hello!");
                    out.flush();

                    System.out.println("Other side says: " + in.readUTF());

                    // only reached if connection setup worked!
                    return;
                } catch (Exception e) {
                    System.out.println("Failed to created connection to "
                            + target[i]);
                } finally {
                    DirectSocketFactory.close(s, out, in);
                }
            }
        }
    }

    private static void server() throws IOException {

        System.out.println("Creating server socket");

        DirectSimpleSocket s1 = null;
        DirectSimpleSocket s2 = null;

        DataInputStream in1 = null;
        DataInputStream in2 = null;

        DataOutputStream out1 = null;
        DataOutputStream out2 = null;

        try {
            DirectServerSocket ss = sf.createServerSocket(LOCAL_PORT, 0, null);
            ss.setReuseAddress(true);

            System.out.println("Created server on " + ss.getAddressSet());

            // Get two connections
            s1 = (DirectSimpleSocket) ss.accept();

            InetSocketAddress address1 =
                (InetSocketAddress) s1.getRemoteSocketAddress();

            System.out.println("Incoming connection from " + address1);

            in1 = new DataInputStream(s1.getInputStream());
            out1 = new DataOutputStream(s1.getOutputStream());

            String key1 = in1.readUTF();

            System.out.println("Connection for key: " + key1);

            // Get two connections
            s2 = (DirectSimpleSocket) ss.accept();

            InetSocketAddress address2 =
                (InetSocketAddress) s2.getRemoteSocketAddress();

            System.out.println("Incoming connection from " + address2);

            in2 = new DataInputStream(s2.getInputStream());
            out2 = new DataOutputStream(s2.getOutputStream());

            String key2 = in2.readUTF();

            System.out.println("Connection for key: " + key2);

            if (!key1.equals(key2)) {
                System.out.println("Keys don't match! " + key1 + " " + key2);
                return;
            }

            System.out.println("Keys match, sending reply...");

            out1.writeUTF(address2.getAddress().toString());
            out1.writeInt(address2.getPort());
            out1.flush();

            out2.writeUTF(address1.getAddress().toString());
            out2.writeInt(address1.getPort());
            out2.flush();

        } catch (Exception e) {
            System.out.println("Oops " + e);
        } finally {
            DirectSocketFactory.close(s1, out1, in1);
            DirectSocketFactory.close(s2, out2, in2);
        }

        System.out.println("Done!");
    }

    public static void main(String [] args) throws IOException {

        if (args.length == 2) {
            client(args[0], DirectSocketAddress.getByAddress(args[1]));
        } else {
            server();
        }
    }
}
