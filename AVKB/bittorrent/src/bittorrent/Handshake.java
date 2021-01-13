package bittorrent.src.bittorrent;

import java.io.*;
import java.lang.reflect.Array;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;


public class Handshake implements Callable<Handshake>{


	String IP;
	int port;
	byte[] infoHash;
	String peerID;
	AtomicInteger numPiecesReceived;
	AtomicInteger bytesReceived;
	ConcurrentLinkedDeque<Integer> pieces;
	String fileName;
	int numPieces;
	int PIECE_SIZE;
	int fileSize;
	byte[][] pieceHashes;

	public Handshake(String IP, int port, byte[] infoHash, String peerID, AtomicInteger numPiecesReceived,
			AtomicInteger bytesReceived, ConcurrentLinkedDeque<Integer> pieces, String fileName, int numPieces,
			int PIECE_SIZE, int fileSize, byte[][] pieceHashes) {
		this.IP = IP;
		this.port = port;
		this.infoHash = infoHash;
		this.peerID = peerID;
		this.numPiecesReceived = numPiecesReceived;
		this.bytesReceived = bytesReceived;
		this.pieces = pieces;
		this.fileName = fileName;
		this.numPieces = numPieces;
		this.PIECE_SIZE = PIECE_SIZE;
		this.fileSize = fileSize;
		this.pieceHashes = pieceHashes;
	}

	public int peerConnect() {

		
		SocketChannel socket;
		try {
			socket = SocketChannel.open();
			

			HandshakeMessage message = new HandshakeMessage(this.infoHash, this.peerID);
			
			InetAddress peerIP = InetAddress.getByName(IP);
			ByteBuffer handBack = ByteBuffer.allocate(68);
			ByteBuffer handMessage = message.buffer;
			
			ByteBuffer peerIDIn = ByteBuffer.allocate(20);
			ByteBuffer infoHashIn = ByteBuffer.allocate(20);
			byte[] dataIn;
			boolean connection;


			connection = socket.connect(new InetSocketAddress(peerIP, port));			

			handMessage.flip();
			
			int bytesWritten = 0;
			while(handMessage.hasRemaining()) {
				bytesWritten+= socket.write(handMessage);
			}

	
			int bytesRead = socket.read(handBack);

			
			if(!(bytesRead > 0)) {
				socket.close();
				
				return -1;
			} 

			
			
			dataIn = handBack.array();

	

			for(int i = 28; i < 48; i++) {
				infoHashIn.put(dataIn[i]);
				
			}
			for(int i = 48; i < 68; i++) {
				peerIDIn.put(dataIn[i]);
				
			}

			/*
				MessageHandler(String fileName, ConcurrentLinkedDeque<Integer> pieces, AtomicInteger numPiecesReceived,
				AtomicInteger bytesReceived, SocketChannel channel, int numPieces, int pieceSize, byte[][] pieceHashes) 
			*/
		
			socket.configureBlocking(false);
			MessageHandler handler = new MessageHandler(fileName, pieces, numPiecesReceived, bytesReceived, socket, numPieces, PIECE_SIZE, fileSize,
			 pieceHashes);
			
			handler.handleMessages();
			

		} catch (IOException e) {
			System.out.println("Handshake connection refused");
		}
		return 1;
	}

		
	
	public class HandshakeMessage {
		private static final String BITTORRENT_PROTOCOL_IDENTIFIER = "BitTorrent protocol";
		private static final int BASE_HANDSHAKE_LENGTH = 49;
		private int pstrlen = BITTORRENT_PROTOCOL_IDENTIFIER.length();
		private ByteBuffer peerID = ByteBuffer.allocate(20);
		private ByteBuffer infoHash = ByteBuffer.allocate(20);
		private ByteBuffer buffer;
		
		private HandshakeMessage(byte[] infoHash, String peerID) {
			
			this.infoHash.put(infoHash);
			this.peerID.put(peerID.getBytes());
			
			buffer = ByteBuffer.allocate(BASE_HANDSHAKE_LENGTH + pstrlen);
			byte[] reserved = new byte[8];
			buffer.put((byte) pstrlen);
			buffer.put(BITTORRENT_PROTOCOL_IDENTIFIER.getBytes());
			buffer.put(reserved);
			buffer.put(this.infoHash.array());
			buffer.put(this.peerID.array());
		}		
	}

	@Override
	public Handshake call() throws Exception {
		return this;
	}
		
	
	
}
