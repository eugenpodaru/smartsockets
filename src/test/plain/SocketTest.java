package test.plain;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

public class SocketTest {

    private final static int PORT = 8899;
    private final static int REPEAT = 10;
    private final static int COUNT = 1000;

    public static void main(String[] args) {

        int targets = args.length;
        int repeat = REPEAT;        
        int count = COUNT;
        
        boolean pingpong = false;
        
        for (int i=0;i<args.length;i++) { 
            if (args[i].equals("-repeat")) { 
                repeat = Integer.parseInt(args[i+1]);
                args[i+1] = null;
                args[i] = null;
                targets -= 2;
                i++;
                
            } else if (args[i].equals("-count")) { 
                count = Integer.parseInt(args[i+1]);
                args[i+1] = null;
                args[i] = null;
                targets -= 2;
                i++;
        
            } else if (args[i].equals("-pingpong")) {
                pingpong = true;
                args[i] = null;
                targets--;
            }
        }
        
        InetSocketAddress [] targetAds = new InetSocketAddress[targets];
        int index = 0;
        
        for (int i=0;i<args.length-1;i++) { 
            if (args[i] != null && args[i+1] != null) { 
                targetAds[index++] = new InetSocketAddress(args[i], 
                        Integer.parseInt(args[i+1])); 
            }
        } 
        
        try {
            if (index > 0) {

                for (InetSocketAddress a : targetAds) {
                    
                    if (a == null) { 
                        continue;
                    }
                    
                    System.out.println("Creating connection to " + a);
                    
                    Socket s = null;
                    
                    for (int r = 0; r < repeat; r++) {
                    
                        long time = System.currentTimeMillis();
                        
                        int failed = 0;
                        
                        for (int c = 0; c < count; c++) {
                            
                            if (s == null) {                             
                                s = new Socket();
                                s.setReuseAddress(true);
                            }
                            
                            try { 
                                s.connect(a, 1000);
                                
                            /*    if (pingpong) { 
                                    s.setTcpNoDelay(true);
                                    
                                    OutputStream out = s.getOutputStream();

                                    out.write(42);
                                    out.flush();
                                
                                    InputStream in = s.getInputStream();
                                    in.read();
                                
                                    in.close();
                                    out.close();
                                } */
                                
                                s.close();
                                s = null;
                            } catch (Exception e) {
                                System.err.println("" + e);
                                failed++;                                
                            }
                        }
                     
                        time = System.currentTimeMillis() - time;

                        System.out.println(count + " connections in " + time 
                                + " ms. -> " + (((double) time) / count) 
                                + "ms/conn");
                       
                    }
                }
            } else {

                System.out.println("Creating server socket");

                ServerSocket ss = new ServerSocket(PORT, 100);

                System.out.println("Created server on " + ss.toString());

                while (true) {
                    Socket s = ss.accept();
                    
/*                    if (pingpong) { 
                        s.setTcpNoDelay(true);
                        
                        InputStream in = s.getInputStream();
                        in.read();
                    
                        OutputStream out = s.getOutputStream();

                        out.write(42);
                        out.flush();
                    
                        in.close();
                        out.close();
                    }
                     */
                    s.close();
                }
            }

        } catch (Exception e) {
            System.out.println("EEK!");
            e.printStackTrace(System.err);
        }
    }
}
