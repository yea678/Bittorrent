package bittorrent.src.bittorrent;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.BitSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;


public class Seeder {
    // Add local variable of port
    public static void seed(int port, String infoHash, String peerID, String fileName, AtomicInteger bytesUploaded) {

        System.out.println("Seeder opening serverSocketChannel");
        ServerSocketChannel serverSocketChannel;
        try {
            serverSocketChannel = ServerSocketChannel.open();
            Seeder s = new Seeder();

            // Change the IP address, this is just generic
            serverSocketChannel.socket().bind(new InetSocketAddress(InetAddress.getLocalHost(), port));

            System.out.println("Seeder serverSocketChannel reading input");
            while (true) {
                SocketChannel socketChannel = serverSocketChannel.accept();

                if (socketChannel != null) {
                    System.out.println("Seeder received message");
                    ByteBuffer input = ByteBuffer.allocate(68);
                    socketChannel.read(input);

                    // position() gets the size of the bytebuffer, 68 is the length of a handshake
                    // message
                    if (input.position() == 68) {
                        System.out.println("Seeder crafting handshake message");
                        HandshakeMessage message = s.new HandshakeMessage(infoHash, peerID);
                        ByteBuffer handshakeOut = message.getMessage();
                        System.out.println("Seeder sending handshake message");
                        socketChannel.write(handshakeOut);
                    }

                    System.out.println("Seeder entering handleClient");
                    handleClient(socketChannel, fileName, bytesUploaded);
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void handleClient(SocketChannel channel, String fileName, AtomicInteger bytesUploaded) {

        /*
         * for a given amount of time run a loop sending/messages messages to the peer
         */
        long loopTimeout = 10000;
        // this is the timeout for the loop
        long timeoutMili = 10000;
        
        Seeder s = new Seeder();
        
        // First send our bitfield. Will need to add bitset to handshake class ans make
        // a getter or something
        BitFieldMessage sendField = s.new BitFieldMessage();
        try {
            channel.configureBlocking(false);
            channel.write(sendField.buff);
        } catch (IOException e) {
            e.printStackTrace();
        }
  
            try {
                int sizeReceived = 0;
                long startTime = System.currentTimeMillis(); //fetch starting time

                while (System.currentTimeMillis() - startTime < timeoutMili) {

                        ByteBuffer lengthBuffer = ByteBuffer.allocate(4);
                        sizeReceived = channel.read(lengthBuffer);
                    if (sizeReceived == 4) {

                        // first recieve the length to check if it's a keepAlive message
                        
                        int length = lengthBuffer.getInt(0);

                        // Keep Alive Message. Reset timeout
                        if (length == 0) {
                            startTime = System.currentTimeMillis();
                            continue;
                        }
                        // Recieve type and match the message
                        ByteBuffer idBuffer = ByteBuffer.allocate(1);
                        sizeReceived = channel.read(idBuffer);
                        if (sizeReceived == 0) {
                            continue;
                        }
                        byte id = idBuffer.get();

                        // Choke Message (shouldn't receive this)
                        if (id == 0) {
                            System.out.println("Wrong type received while Seeding");
                        // Unchoke Message (shouldn't receive this)
                        } else if (id == 1) {
                            System.out.println("Wrong type received while Seeding");
                        // Interested Message 
                        } else if (id == 2) {
                            //<len=0001><id=2>
                            //send back unchoke
                            UnchokeMessage message = s.new UnchokeMessage();
                            channel.write(message.buff);

                        // Not Interested Message 
                        } else if (id == 3) {
                            //<len=0001><id=3> 
                            return;

                        // Have Message (shouldn't receive this)
                        } else if (id == 4) {
                            // <len=0005><id=4><piece index>
                            System.out.println("Wrong type received while Seeding");
                        // BitField Message (shouldn't receive this)
                        } else if (id == 5) {
                            // <len=0001+X><id=5><bitfield>
                            System.out.println("Wrong type received while Seeding");
                        // Request message 
                        } else if (id == 6) {
                            //<len=0013><id=6><index><begin><length>
                            ByteBuffer index = ByteBuffer.allocate(4);
                            sizeReceived = channel.read(index);
                            ByteBuffer begin = ByteBuffer.allocate(4);
                            sizeReceived = channel.read(begin);
                            ByteBuffer lengthRead = ByteBuffer.allocate(4);
                            sizeReceived = channel.read(lengthRead);

                            //chirag's function
                            byte[] piece = MessageHandler.getPieceFromFile(index.getInt(0), begin.getInt(0), lengthRead.getInt(0), fileName);

                            PieceMessage message = s.new PieceMessage(index.getInt(0), begin.getInt(0), piece);
                            int sendSize = message.length;
                            ByteBuffer toSend = message.buff;

                            //send message
                            long loopStartTime = System.currentTimeMillis();
                            while(sendSize > 0) {
                                //loopTimeout
                                if (System.currentTimeMillis() - loopStartTime < loopTimeout) {
                                    return;
                                }
                                sendSize -= channel.write(toSend);
                            }
                            bytesUploaded.addAndGet(message.length);

                        // Piece Message (shouldn't receive this)
                        } else if (id == 7) {
                            // <len=0009+X><id=7><index><begin><block>. byteAmount = block size
                            System.out.println("Wrong type received while Seeding");
                        // Cancel Message ???
                        } else if (id == 8) {
                            System.out.println("Wrong type received while Seeding");
                        // Error (shouldn't happen)
                        } else {
                            System.out.println("Wrong type received while Seeding");
                        }

                    }
                    
                //reset start time since a message was received by the peer
                startTime = System.currentTimeMillis();
            
            }
    
                // maybe check and make sure the channel closed properly
                channel.close();
            } catch (IOException e2) {
                e2.printStackTrace();
            }
    
    }


    // Copy paste of my HandshakeMessage to use to generate the handshake message to send

    private class HandshakeMessage {
        private static final String BITTORRENT_PROTOCOL_IDENTIFIER = "BitTorrent protocol";
        private static final int BASE_HANDSHAKE_LENGTH = 49;
        private int pstrlen = BITTORRENT_PROTOCOL_IDENTIFIER.length();
        private ByteBuffer peerID;
        private ByteBuffer infoHash;
        
        private HandshakeMessage(String infoHash, String peerID) {
            this.infoHash.put(infoHash.getBytes());
            this.peerID.put(peerID.getBytes());
        }
        
        private ByteBuffer getMessage() {
        
            ByteBuffer buffer = ByteBuffer.allocate(BASE_HANDSHAKE_LENGTH + pstrlen);
            byte[] reserved = new byte[8];
            buffer.put((byte)pstrlen);
            buffer.put(BITTORRENT_PROTOCOL_IDENTIFIER.getBytes());
            buffer.put(reserved);
            buffer.put(infoHash);
            buffer.put(peerID);
            return buffer;
        }
        
    }

    //MESSAGES

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


	//<len=0001+X><id=5><bitfield>
	public class BitFieldMessage {
		/*depends on the size of the bitfield.
		Optional, may only need to recieve this
		*/
		int length;
		byte id = 5;
		ByteBuffer buff = null;
		public BitFieldMessage () {
            BitSet ourBitfield = Driver.getBitfield();

			length = 1 + ourBitfield.length();
			buff = ByteBuffer.allocate(length + 4);
			buff.putInt(length);
			buff.put(id);
			buff.put(ourBitfield.toByteArray());
		}
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

        System.out.println("RMESSAGE: " + index + " " + begin + " " + requestLength);

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