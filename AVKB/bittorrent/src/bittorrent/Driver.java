package bittorrent.src.bittorrent;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import bittorrent.src.Bencode.src.main.java.be.adaxisoft.bencode.BEncodedValue;


public class Driver {
    static Object lock = new Object();
    static BitSet ourBitfield;
    static ConcurrentLinkedDeque<Integer> pieces;
    static AtomicInteger numPiecesReceived;
    static AtomicInteger bytesReceived;
    static AtomicInteger bytesUploaded;
    static int port;
    static String infoHash;
    static String peerID;
    static String fileName;

    static class PingTimer extends TimerTask{
        
        int size; 
        AtomicInteger bytesUploaded;
        AtomicInteger bytesDownloaded;
        Tracker tracker;
        public PingTimer(AtomicInteger bytesUploaded, AtomicInteger bytesDownloaded, int fileSize, Tracker t){
            this.bytesUploaded = bytesUploaded;
            this.bytesDownloaded = bytesDownloaded;
            this.size = fileSize;
            this.tracker = t;
        }

        public void run(){
          /* int remaining = size - bytesUploaded.intValue();
            tracker.pingTracker(remaining);
        */
            tracker.pingTracker(this.bytesDownloaded.intValue(), this.bytesUploaded.intValue());
        }
    }


    public static boolean bitfieldHasPiece(int piece) {
        boolean hasPiece = false;
        synchronized (lock) {
            hasPiece = ourBitfield.get(piece);
        }
        return hasPiece;
    }

    public static int getPort() {
        return port;
    }

    public static String getInfoHash() {
        return infoHash;
    }

    public static String getPeerID() {
        return peerID;
    }

    public static String getFileName() {
        return fileName;
    }

    public static AtomicInteger getPiecesUploaded(){
        return bytesUploaded;
    }

    public static void setPiece(int piece) {
        synchronized (lock) {
            ourBitfield.set(piece);
        }
    }

    public static BitSet getBitfield() {
        return ourBitfield;
    }

    public static void initializePieces(int numPieces) {
		for (int i = 0; i < numPieces; i++) {
			pieces.addLast(i);
		}
	}

    public static void main(String[] args) {
        // try {
        //     PrintStream out = new PrintStream(new FileOutputStream("output.txt"));
        // System.setOut(out);
        // } catch (FileNotFoundException e) {
        //     e.printStackTrace();
        // }
        
        Tracker tracker = new Tracker(args[0]);
        String[] peerIps = tracker.getPeers();


        int numProcessors = Runtime.getRuntime().availableProcessors();
        ExecutorService threadPool = Executors.newFixedThreadPool(numProcessors);
        List<Callable<Handshake>> tasks = new LinkedList<Callable<Handshake>>();
        
        //Decide how to name files
        String fileName;
        if (args.length == 2) {
            fileName = args[1];
        } else {
            fileName = tracker.getTorFileName();
        }


        int fileSize = tracker.getFileLength();
        int sizePieces = tracker.getPieceLength();
        int numPieces = fileSize/sizePieces + 1;
        System.out.println("Total number of pieces: " + numPieces);

        pieces = new ConcurrentLinkedDeque<Integer>();
        initializePieces(numPieces);

        numPiecesReceived = new AtomicInteger(0);
        bytesReceived = new AtomicInteger(0);
        bytesUploaded = new AtomicInteger(0);
        ourBitfield = new BitSet();

        //setting timer to ping tracker
        Timer timer = new Timer();
        TimerTask task = new PingTimer(bytesUploaded,bytesReceived, fileSize, tracker);
        int interval = tracker.getInterval();

        timer.schedule(task, 0, interval * 1000);
 
        ArrayList<Thread> threadList = new ArrayList<Thread>();
        int threads = 0;
        AtomicInteger indexPeer = new AtomicInteger(0);
        for(int i = 0; i < 5; i++) {
            Runnable r1 = new Runnable() {

				@Override
				public void run() {
                    while(bytesReceived.get() < fileSize) {
                        if (indexPeer.get() >= peerIps.length) {
                            indexPeer.set(0);
                        }
                        String s = peerIps[indexPeer.get()];
                        indexPeer.getAndIncrement();
                    
                        String[] splitIp = s.split(":");
                        String ip = splitIp[0];
                        int port2 = Integer.parseInt(splitIp[1]);
                        Handshake tester = new Handshake(ip, port2, tracker.getHash(), tracker.getPeerId(),
                            numPiecesReceived, bytesReceived, pieces, fileName, numPieces, sizePieces, fileSize, tracker.getPieceHashes());

                        tester.peerConnect();
                    }
					
				}
                
            };
            Thread t1 = new Thread(r1);
            threadList.add(t1);
            t1.start();
        }

        for(Thread t: threadList) {
            try {
                t.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

       
        Thread seedThread = new SeederThread();
        
        seedThread.start();

        
        
    }

}


class SeederThread extends Thread {

    static int port = Driver.getPort();
    static String infoHash = Driver.getInfoHash();
    static String peerID = Driver.getPeerID();
    static String fileName = Driver.getFileName();
    static AtomicInteger bytesUploaded = Driver.getPiecesUploaded();

    public void run() {
        Seeder.seed(port, infoHash, peerID, fileName, bytesUploaded);
    }
}
