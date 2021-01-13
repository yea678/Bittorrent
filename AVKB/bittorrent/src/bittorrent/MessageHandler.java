package bittorrent.src.bittorrent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.BitSet;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/*
 * Needs a list of the pieces to request
 * 
 * Will need a way to access the list safely with multi-threading
 * need a way for all peers to know the current piece to request
 * (Are we allowed to use Atomic Integer or something similar? could represent
 * the current piece to request)
 * 
 * NOTE*:In our main method we will always need to be ready to receive a 'Have' request
 * from another peer.
 */
public class MessageHandler {
	SocketChannel channel;

	final int BLOCK_SIZE = 16384;
	// This info is in the Torrent file. Idk what happens if multiple files are
	// downloading, maybe make a file class
	static int PIECE_SIZE;

	// get the number of Pieces from maxim
	int numPieces;
	BitSet peerBitfield = null;

	public int timeoutMili = 1000;

	String fileName;
	// This will determine which piece has been recieved so far. These will need to
	// be shared by all threads, so either static or make this an inner class or
	// something
	ConcurrentLinkedDeque<Integer> pieces;
	AtomicInteger numPiecesReceived;
	AtomicInteger bytesReceived;
	BitSet ourBitfield;
	byte[][] pieceHashes;
	int fileSize;

	public MessageHandler(String fileName, ConcurrentLinkedDeque<Integer> pieces, AtomicInteger numPiecesReceived,
			AtomicInteger bytesReceived, SocketChannel channel, int numPieces, int pieceSize, int fileSize,
			byte[][] pieceHashes) {
		this.fileName = fileName;
		this.pieces = pieces;
		this.numPiecesReceived = numPiecesReceived;
		this.numPieces = numPieces;
		PIECE_SIZE = pieceSize;
		this.pieceHashes = pieceHashes;
		this.bytesReceived = bytesReceived;
		this.fileSize = fileSize;
		this.channel = channel;
	}

	/*
	 * This will have to be done at some point
	 * 
	 * How can we keep track of the pieces once they are written? (If we are
	 * seeding)
	 */
	public static void writeToFile(byte[] pieceToWrite, int piece, String name) {// Chirag
		try {
			RandomAccessFile writer = new RandomAccessFile(name, "rw");
			FileChannel channel = writer.getChannel();
			ByteBuffer buff = ByteBuffer.wrap(pieceToWrite);
			int pos = piece * PIECE_SIZE;

			channel.write(buff, pos);

			writer.close();
		} catch (Exception e) {

		}

	}

	public static byte[] getPieceFromFile(int piece, int begin, int length, String name) {// Chirag
		try {
			RandomAccessFile reader = new RandomAccessFile(name, "r");
			FileChannel channel = reader.getChannel();
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			int pos = (piece * PIECE_SIZE) + begin;

			ByteBuffer buff = ByteBuffer.allocate(length);
			while (channel.read(buff, pos) > 0) {
				out.write(buff.array(), 0, buff.position());
				buff.clear();
			}
			byte[] arr = out.toByteArray();
			reader.close();
			return arr;
		} catch (Exception e) {

		}
		return new byte[1];
	}

	/*
	 * responsible for requesting the correct next piece from the client
	 */
	public void requestBlock(int piece, int begin, int length) {// Alex
		// if the queue is empty
		if (piece == -1) {
			return;
		}
		// <len=0013><id=6><index><begin><length>
		RequestMessage message = new RequestMessage(piece, begin, length);
		RequestMessage messageCheck = new RequestMessage(piece, begin, length);
		int x = messageCheck.buff.getInt(5);
		int y = messageCheck.buff.getInt(9);
		int z = messageCheck.buff.getInt(13);

		try {
			int amountSent = 0;
			/** ADD TIMEOUT SO THERE IS NO INFINITE LOOP */
			while (amountSent < 17) {

				amountSent += channel.write(message.buff);
			}

		} catch (IOException e) {
			e.printStackTrace();
		}

	}

	/*
	 * determines which piece should be requested next
	 */
	public int getNextPiece() {// Alex
		if (pieces.peek() == null) {
			return -1;
		}
		// return and increment
		return pieces.poll();
	}

	// What if this doesn't send
	public void sendInterested() {
		InterestedMessage message = new InterestedMessage();
		try {
			channel.write(message.buff);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public void sendNotInterested() {
		NotInterestedMessage message = new NotInterestedMessage();
		try {
			channel.write(message.buff);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/*
	 * handles all messages for a given peer after the connection is established
	 * Let's just start and assume we are given the socket connected to the peer
	 */
	public void handleMessages() {// Alex
		/*
		 * for a given amount of time run a loop sending/messages messages to the peer
		 */
		boolean outstandingRequest = false;
		Piece currentPiece = null;
		long loopTimeout = 1000;
		sendInterested();
		try {
			channel.configureBlocking(false);
			boolean unchoked = false;

			long startTime = System.currentTimeMillis(); // fetch starting time

			/*
			 * for(given amount of time or until failure) { Check for any incoming data: if
			 * there is a incoming message: process else: request next piece/block }
			 * 
			 * Note: Everytime data is read, should check the correct amount of bytes are
			 * read
			 */
			while (true) {
				if (System.currentTimeMillis() - startTime > timeoutMili) {
					break;
				}
				// System.out.println("allocating lengthBuffer");
				ByteBuffer lengthBuffer = ByteBuffer.allocate(4);
				// System.out.println("reading from the channel");
				int sizeReceived = channel.read(lengthBuffer);

				// ready to read in data
				if (sizeReceived == 4) {
					// ready for reading
					int length = lengthBuffer.getInt(0);

					// Keep Alive Message. Reset timeout
					if (length == 0) {
						startTime = System.currentTimeMillis();
						continue;
					}
					// Recieve type and match the message
					ByteBuffer idBuffer = ByteBuffer.allocate(1);
					sizeReceived = channel.read(idBuffer);
					idBuffer.flip();
					if (sizeReceived == 0) {
						continue;
					}
					byte id = idBuffer.get();

					// Choke Message
					if (id == 0) {

						unchoked = false;
						// Unchoke Message
					} else if (id == 1) {
						unchoked = true;
						// Interested Message (shouldn't happen. receive data do nothing)
					} else if (id == 2) {
						continue;
						// Not Interested Message (shouldn't happen. receive data do nothing)
					} else if (id == 3) {
						continue;
						// Have Message
					} else if (id == 4) {
						// <len=0005><id=4><piece index>
						ByteBuffer index = ByteBuffer.allocate(4);
						sizeReceived = channel.read(index);
						peerBitfield.set(index.getInt(0));
						// BitField Message
					} else if (id == 5) {
						// <len=0001+X><id=5><bitfield>
						ByteBuffer field = ByteBuffer.allocate(length - 1);
						sizeReceived = channel.read(field);

						// add timeout
						long loopStartTime = System.currentTimeMillis();
						while (field.hasRemaining()) {
							// loopTimeout
							if (System.currentTimeMillis() - loopStartTime > loopTimeout) {
								break;
							}
							sizeReceived += channel.read(field);
						}
						field.flip();
						// set peer bitfield
						peerBitfield = BitSet.valueOf(field);
						// Request message (shouldn't happen. receive data do nothing)
					} else if (id == 6) {
						ByteBuffer index = ByteBuffer.allocate(12);
						sizeReceived = channel.read(index);

						// Piece Message
					} else if (id == 7) {
						// <len=0009+X><id=7><index><begin><block>. byteAmount = block size
						if (currentPiece == null) {
							continue;
						}

						ByteBuffer index = ByteBuffer.allocate(4);
						sizeReceived = channel.read(index);
						ByteBuffer begin = ByteBuffer.allocate(4);
						sizeReceived = channel.read(begin);

						// if we got data for the wrong piece or it gave data with the wrong offset
						if (index.getInt(0) != currentPiece.pieceNumber
								|| begin.getInt(0) != currentPiece.bytesReceived) {
							// RESET REQUEST?
							break;
						}

						int byteAmount = length - 9;
						ByteBuffer data = ByteBuffer.allocate(byteAmount);
						sizeReceived = channel.read(data);

						long loopStartTime = System.currentTimeMillis();
						while (sizeReceived < byteAmount) {
							// loopTimeout
							if (System.currentTimeMillis() - loopStartTime > loopTimeout) {
								break;
							}
							sizeReceived += channel.read(data);
						}
						int check = currentPiece.addData(byteAmount, data);

						// too many bytes received. Reset request
						if (check == -1) {
							// RESET REQUEST?
							continue;
						}

						/*
						 * only allow another request to be sent if all of the data requested has been
						 * recieved. (received BLOCK_SIZE or the currentPiece has received all the data)
						 */
						outstandingRequest = false;
						// Cancel Message (shouldn't happen. receive data do nothing)
					} else {
						System.out.println("Wrong Handshake Message type received");
						break;
					}

					// reset start time since a message was received by the peer
					startTime = System.currentTimeMillis();
				} else if (unchoked) {
					if (currentPiece != null && currentPiece.isDone()) {
						// System.out.println("RealHash: "+
						// bytesToHex(pieceHashes[currentPiece.pieceNumber]));
						// turn null into the hash from the torrent file
						// if (currentPiece.getHash().equals(pieceHashes[currentPiece.pieceNumber])){
						if (true) {
							// if it passes the hash add it to our list of pieces
							writeToFile(currentPiece.data.array(), currentPiece.pieceNumber, fileName);
							numPiecesReceived.incrementAndGet();
							bytesReceived.addAndGet(currentPiece.pieceSize);
							// USE SETTER
							Driver.setPiece(currentPiece.pieceNumber);

						} else {
							pieces.addFirst(currentPiece.pieceNumber);
						}
						currentPiece = null;
						// if we havent made a request send a request
					} else if (!outstandingRequest) {
						// if this is the first piece to request
						if (currentPiece == null) {
							int nextPiece = getNextPiece();
							if (nextPiece != -1)
								System.out.println("Getting piece #" + nextPiece);
							// System.out.println(numPieces);

							// if all of the pieces are currently
							if (nextPiece == -1) {
								continue;

								// If our peer does not have the piece we want
							} else if (peerBitfield.get(nextPiece) == false) {
								pieces.addFirst(nextPiece);
								break;
							}

							if (nextPiece == numPieces - 1) {
								// last piece is = file size - all but the last piece bytes
								currentPiece = new Piece(nextPiece, (fileSize - (PIECE_SIZE * (numPieces - 1))));
							} else {
								currentPiece = new Piece(nextPiece, PIECE_SIZE);
							}

							// request the new data. *DOUBLE CHECK THAT BEGIN IS CORRECT
							requestBlock(currentPiece.pieceNumber, currentPiece.bytesReceived,
									Math.min(BLOCK_SIZE, currentPiece.pieceSize));

							outstandingRequest = true;

						} else {
							// We need to request
							requestBlock(currentPiece.pieceNumber, currentPiece.bytesReceived,
									Math.min(BLOCK_SIZE, (currentPiece.pieceSize - currentPiece.bytesReceived)));
							outstandingRequest = true;
						}
					}

					if (numPiecesReceived.get() == numPieces) {

						System.out.println("File has finished downloading");
						break;
					}
				}
			}
			// maybe check and make sure the channel closed properly
			if (currentPiece != null) {
				pieces.addFirst(currentPiece.pieceNumber);
			}
			channel.close();
		} catch (IOException e2) {
			e2.printStackTrace();
		}

	}

	private static final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();

	public static String bytesToHex(byte[] bytes) {
		char[] hexChars = new char[bytes.length * 2];
		for (int j = 0; j < bytes.length; j++) {
			int v = bytes[j] & 0xFF;
			hexChars[j * 2] = HEX_ARRAY[v >>> 4];
			hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
		}
		return new String(hexChars);
	}

	public boolean fileIsDownloaded() {
		return (numPiecesReceived.get() == numPieces);
	}

	// this has the information for the current piece being downloaded
	public class Piece {
		ByteBuffer data;
		int bytesReceived = 0;
		int pieceNumber;
		int pieceSize;
		byte[] hash;

		public Piece(int pieceNumber, int pieceSize) {
			this.pieceNumber = pieceNumber;
			this.pieceSize = pieceSize;
			data = ByteBuffer.allocate(pieceSize);
			hash = null;
		}

		public boolean isDone() {
			return (bytesReceived == pieceSize);
		}

		public int addData(int size, ByteBuffer dataToAdd) {
			if (bytesReceived + size <= PIECE_SIZE) {
				data.put(dataToAdd.array());
				bytesReceived += size;
				return 0;
			}

			// Error, too much data was received
			return -1;
		}

		// Figure out how to get th hash for all the Bytes
		public byte[] getHash() {
			if (!isDone()) {
				return null;
			}
			try {
				MessageDigest md = MessageDigest.getInstance("SHA-1");
				hash = md.digest(data.array());
			} catch (NoSuchAlgorithmException e) {
				e.printStackTrace();
			}
			return hash;
		}
	}

	/*
	 * List of the different messages you can send an recieve. buff is the data to
	 * send
	 */

	// <len=0000>
	public class KeepAliveMessage {
		int length = 0;
		ByteBuffer buff = null;

		public KeepAliveMessage() {
			buff = ByteBuffer.allocate(length + 4);
			buff.putInt(length);
			buff.flip();
		}
	}

	// <len=0001><id=0>
	public class ChokeMessage {
		int length = 1;
		byte id = 0;
		ByteBuffer buff = null;

		public ChokeMessage() {
			buff = ByteBuffer.allocate(length + 4);
			buff.putInt(length);
			buff.put(id);
			buff.flip();

		}
	}

	// <len=0001><id=1>
	public class UnchokeMessage {
		int length = 1;
		byte id = 1;
		ByteBuffer buff = null;

		public UnchokeMessage() {
			buff = ByteBuffer.allocate(length + 4);
			buff.putInt(length);
			buff.put(id);
			buff.flip();

		}
	}

	// <len=0001><id=2>
	public class InterestedMessage {
		int length = 1;
		byte id = 2;
		ByteBuffer buff = null;

		public InterestedMessage() {
			buff = ByteBuffer.allocate(length + 4);
			buff.putInt(length);
			buff.put(id);
			buff.flip();

		}
	}

	// <len=0001><id=3>
	public class NotInterestedMessage {
		int length = 1;
		byte id = 3;
		ByteBuffer buff = null;

		public NotInterestedMessage() {
			buff = ByteBuffer.allocate(length + 4);
			buff.putInt(length);
			buff.put(id);
			buff.flip();

		}
	}

	// <len=0005><id=4><piece index>
	public class HaveMessage {
		// Possible issue with the signed bit
		int length = 5;
		byte id = 4;
		ByteBuffer buff = null;

		public HaveMessage(int piece) {
			// 4 = size of length, 5= size of piece + identifier
			buff = ByteBuffer.allocate(length + 4);
			buff.putInt(length);
			buff.put(id);
			// might want to validate the piece
			buff.putInt(piece);
			buff.flip();

		}
	}

	// <len=0001+X><id=5><bitfield> (get this class from seeder)
	public class BitFieldMessage {
		/*
		 * depends on the size of the bitfield. Optional, may only need to recieve this
		 */
		int length;
		byte id = 5;
		ByteBuffer buff = null;
	}

	// <len=0013><id=6><index><begin><length>
	public class RequestMessage {
		int length = 13;
		byte id = 6;
		int index = 0;
		int begin = 0;
		int requestLength = 0;
		ByteBuffer buff = null;

		public RequestMessage(int index, int begin, int requestLength) {
			this.index = index;
			this.begin = begin;
			this.requestLength = requestLength;

			buff = ByteBuffer.allocate(length + 4);
			buff.putInt(length);
			buff.put(id);
			buff.putInt(index);
			buff.putInt(begin);
			buff.putInt(requestLength);

			buff.flip();

		}
	}

	// <len=0009+X><id=7><index><begin><block>
	public class PieceMessage {
		// need to set length
		int length = 13;
		byte id = 7;
		int index = 0;
		int begin = 0;
		// subject to change
		byte[] block = null;
		ByteBuffer buff = null;

		public PieceMessage(int index, int begin, byte[] block) {
			this.index = index;
			this.begin = begin;
			this.block = block;

			length += block.length;
			buff = ByteBuffer.allocate(length + 4);
			buff.putInt(length);
			buff.put(id);
			buff.putInt(index);
			buff.putInt(begin);
			buff.put(block);
			buff.flip();

		}
	}

	// <len=0013><id=8><index><begin><length>
	public class CancelMessage {
		int length = 13;
		byte id = 8;
		int index = 0;
		int begin = 0;
		int requestedLength = 0;

		ByteBuffer buff = null;

		public CancelMessage(int index, int begin, int requestedLength) {
			this.index = index;
			this.begin = begin;
			this.requestedLength = requestedLength;

			buff = ByteBuffer.allocate(length + 4);
			buff.putInt(length);
			buff.put(id);
			buff.putInt(index);
			buff.putInt(begin);
			buff.putInt(requestedLength);
			buff.flip();

		}
	}

	// Used for DHT tracker. Don't think it will be necessary
	// <len=0003><id=9><listen-port>
	public class PortMessage {
		int length = 3;
		byte id = 9;
	}

}
