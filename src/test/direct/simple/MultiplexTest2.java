package test.direct.simple;


import ibis.smartsockets.direct.DirectServerSocket;
import ibis.smartsockets.direct.DirectSocket;
import ibis.smartsockets.direct.DirectSocketAddress;
import ibis.smartsockets.direct.DirectSocketFactory;
import ibis.smartsockets.util.MultiplexStreamFactory;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;


public class MultiplexTest2 {

    private static final int BLOCK_SIZE = 16*1024;
    private static final int TOTAL_SIZE = 10*1024*1024;

    private static class SenderThread extends Thread {
        private final int number;
        private final int total;
        private final byte [] buffer;
        private final OutputStream out;

        SenderThread(int number, OutputStream out, int size, int total) {
            this.number = number;
            this.out = out;
            this.buffer = new byte[size];
            this.total = total;
        }

        public void run() {

            try {
                int size = 0;

                while (size < total) {

                    if ((total-size) < buffer.length) {
                        //System.out.println(number + " writing " + (total-size));
                        out.write(buffer, 0, total-size);
                        size += (total-size);
                    } else {
                        //System.out.println(number + " writing " + buffer.length);
                        out.write(buffer);
                        size += buffer.length;
                    }

                    out.flush();
                }

                System.out.println("Sender " + number + " wrote " + size
                        + " bytes.");
                out.close();
            } catch (Exception e) {
                System.err.println("Sender got exception " + e);
                e.printStackTrace(System.err);
            }
        }
    }

    public static void main(String [] args) throws IOException {

        DirectSocketFactory sf = DirectSocketFactory.getSocketFactory();

        if (args.length > 0) {

            System.out.println("I am client");

            DirectSocketAddress target = DirectSocketAddress.getByAddress(args[0]);
            int senders = Integer.parseInt(args[1]);

            DirectSocket s = sf.createSocket(target, 0, null);

            System.out.println("Created connection to " + target);

            MultiplexStreamFactory f =
                new MultiplexStreamFactory(s.getInputStream(),
                        s.getOutputStream());

            DataInputStream in = new DataInputStream(f.getBaseIn());
            DataOutputStream out = new DataOutputStream(f.getBaseOut());

            out.writeInt(senders);
            out.flush();

            SenderThread [] threads = new SenderThread[senders];

            System.out.println("Creating " + senders + " streams");

            for (int i=0;i<senders;i++) {
                threads[i] = new SenderThread(i, f.createOutputStream(i),
                        BLOCK_SIZE, TOTAL_SIZE);
            }

            // Wait until the server has set up all the streams.
            in.read();

            System.out.println("Starting test");

            long time = System.currentTimeMillis();

            for (int i=0;i<senders;i++) {
                threads[i].start();
            }

            in.read();

            time = System.currentTimeMillis() - time;

            long size = ((long) TOTAL_SIZE)*senders;
            double sec = time/1000.0;
            double TP = (size / (1024.0*1024.0))/sec;

            System.out.println("Send " + size + " bytes using "
                    + senders + " streams in " + sec + " seconds ("
                    + TP + " MB/sec)");

            out.close();
            in.close();

            f.close();
            s.close();
        } else {
            System.out.println("I am server");

            DirectServerSocket ss = sf.createServerSocket(0, 0, null);

            System.out.println("Created server on " + ss.getAddressSet());

            while (true) {
                DirectSocket s = ss.accept();

                System.out.println("Incoming connection from "
                        + s.getRemoteAddress());

                MultiplexStreamFactory f =
                    new MultiplexStreamFactory(s.getInputStream(),
                            s.getOutputStream());

                DataInputStream in = new DataInputStream(f.getBaseIn());
                DataOutputStream out = new DataOutputStream(f.getBaseOut());

                int senders = in.readInt();

                System.out.println("Accepting " + senders + " senders");

                InputStream [] inputs = new InputStream[senders];
                int [] readData = new int[senders];

                for (int i=0;i<senders;i++) {
                    inputs[i] = f.createInputStream(i);
                }

                // Tell the sender that we are ready to start
                out.write(42);
                out.flush();

                System.out.println("Starting test");

                byte [] buffer = new byte[BLOCK_SIZE];

                int total = TOTAL_SIZE*senders;
                int size = 0;

                while (size < total) {
                    for (int i=0;i<senders;i++) {

                        if (readData[i] < TOTAL_SIZE) {
                            // there is still something to read from this sender
                            int bytes = inputs[i].read(buffer);
                            readData[i] += bytes;
                            size += bytes;
                        }
                    }
                }

                // Close the inputs
                for (int i=0;i<senders;i++) {
                    inputs[i].close();
                }

                out.write(42);
                out.flush();

                System.out.println("done");

                out.close();
                in.close();

                f.close();
                s.close();
            }
        }
    }
}
