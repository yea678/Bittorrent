package bittorrent.src.bittorrent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.net.URL;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URLEncoder;

import bittorrent.src.Bencode.src.main.java.be.adaxisoft.bencode.*;

// Class for handling reading the torrent file and getting necessary data from the tracker
public class Tracker {
    // .torrent file
    String filename;

    // decoded maps from the .torrent file
    Map<String, BEncodedValue> file_dict;
    Map<String, BEncodedValue> info_dict;

    // necessary data from the .torrent file
    String announceUrl;
    String torFileName;
    int length;
    byte[] pieces;
    int pieceLength;

    // data for tracker request
    byte[] infoHash;
    String peerId;

    // interval specified by the tracker response
    int interval;

    public Tracker(String fname) {
        this.filename = fname;

        this.file_dict = this.decode_file();
        this.peerId = "AVKBPeer-for-project";

        // computing hash from encoded info dictionary
        try {
            this.info_dict = this.file_dict.get("info").getMap();
            ByteArrayOutputStream outStream = new ByteArrayOutputStream();
            BEncoder.encode(this.info_dict, outStream);
            byte[] infoDictBytes = outStream.toByteArray();

            MessageDigest md = MessageDigest.getInstance("SHA-1");
            this.infoHash = md.digest(infoDictBytes);
        } catch (NoSuchAlgorithmException e) {
            System.out.println("No such algorithm exception in hash generation");
            e.printStackTrace();
        } catch (InvalidBEncodingException e) {
            System.out.println("InvalidBEncodingException in hash generation");
            e.printStackTrace();
        } catch (IOException e) {
            System.out.println("IOException in hash generation");
        }
    }

    public byte[] getHash() {
        return this.infoHash;
    }

    public String getPeerId() {
        return this.peerId;
    }

    public int getFileLength() {
        return this.length;
    }

    public String getTorFileName() {
        return this.torFileName;
    }

    public int getPieceLength() {
        return this.pieceLength;
    }

    public int getInterval() {
        return this.interval;
    }

    public byte[][] getPieceHashes() {
        int len = this.pieces.length;

        byte[][] out = new byte[len / 20][20];
        for (int i = 0; i < len / 20; i++) {
            for (int j = 0; j < 20; j++) {
                out[i][j] = this.pieces[(i * 20) + j];
            }
        }
        return out;
    }

    public String encodeHash(byte[] hash) {
        String out = "";
        for (int i = 0; i < hash.length; i++) {
            int b = hash[i] & 0xff;
            if ((b < 58 && b > 47) || (b < 91 && b > 64) || (b < 123 && b > 96)) {
                out += (char) b;
            } else {

                out += "%" + (b < 10 ? "0" : "") + Integer.toHexString(b);
            }
        }
        return out;
    }

    public String getReqString(Map<String, Object> params) {
        String output = "";

        // loop through all the keys in the request params dict and form a HTTP get
        // request
        for (String s : params.keySet()) {
            if (output.length() != 0)
                output += "&";
            output += s;
            output += "=";
            output += params.get(s).toString();
        }
        return output;
    }

    @SuppressWarnings("unchecked")
    public Map<String, BEncodedValue> decode_file() {
        Map<String, BEncodedValue> dict = null;
        try {
            // read the torrent file
            File torFile = new File(this.filename);
            FileInputStream inStream = new FileInputStream(torFile);
            BDecoder decoder = new BDecoder(inStream);
            dict = decoder.decodeMap().getMap();

            this.announceUrl = dict.get("announce").getString();
            this.info_dict = dict.get("info").getMap();
            this.length = this.info_dict.get("length").getNumber().intValue();
            this.torFileName = this.info_dict.get("name").getString();
            this.pieces = this.info_dict.get("pieces").getBytes();
            this.pieceLength = this.info_dict.get("piece length").getNumber().intValue();
        } catch (FileNotFoundException e) {
            System.out.println("FileNotFoundException in decode");
            e.printStackTrace();
        } catch (IOException e) {
            System.out.println("IOException in decode");
            e.printStackTrace();
        }

        if (dict == null)
            System.out.println("Error: Unable to decode file");

        return dict;
    }

    @SuppressWarnings("unchecked")
    public String[] getPeers() {
        try {
            // map to hold request parameters
            Map<String, Object> params = new HashMap<>();

            // adding all request parameters
            String encoded_hash = this.encodeHash(this.infoHash);
            params.put("info_hash", encoded_hash);
            params.put("peer_id", this.peerId);
            params.put("port", 6881);
            params.put("uploaded", 0);
            params.put("downloaded", 0);
            params.put("left", this.length);
            params.put("event", "started");
            params.put("compact", 1);

            // prepare the request
            URL url = new URL(this.announceUrl + "?" + getReqString(params));
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Transmission/3.0.0");
            int resCode = conn.getResponseCode();

            InputStream stream = conn.getInputStream();
            BDecoder decoder = new BDecoder(stream);
            Map<String, BEncodedValue> response = decoder.decodeMap().getMap();

            if (resCode != 200) {
                System.out.println("Invalid tracker request");
            } else if (response.get("failure reason") != null) {
                System.out.println("Tracker request failed: " + response.get("failure reason").getString());
            } else {
                byte[] peers = response.get("peers").getBytes();

                String[] ipPorts = new String[peers.length / 6];
                for (int i = 0; i + 5 < peers.length; i += 6) {
                    String toAdd = "";

                    byte ip1Byte = (byte) peers[i];
                    int ip1 = ip1Byte & 0xff;
                    toAdd += ip1 + ".";

                    byte ip2Byte = (byte) peers[i + 1];
                    int ip2 = ip2Byte & 0xff;
                    toAdd += ip2 + ".";

                    byte ip3Byte = (byte) peers[i + 2];
                    int ip3 = ip3Byte & 0xff;
                    toAdd += ip3 + ".";

                    byte ip4Byte = (byte) peers[i + 3];
                    int ip4 = ip4Byte & 0xff;
                    toAdd += ip4 + ":";

                    byte port1Byte = (byte) peers[i + 4];
                    byte port2Byte = (byte) peers[i + 5];
                    int num1 = (port1Byte & 0xff) << 8;
                    int num2 = port2Byte & 0xff;
                    int port = num1 + num2;
                    toAdd += port;

                    ipPorts[i / 6] = toAdd;
                }
                System.out.println("Received " + ipPorts.length + " peers from Tracker");

                this.interval = response.get("interval").getNumber().intValue();
                return ipPorts;
            }
        } catch (MalformedURLException e) {
            System.out.println("Bad URL in get peers");
            e.printStackTrace();
        } catch (IOException e) {
            System.out.println("IOException in get peers");
            e.printStackTrace();
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    public int pingTracker(int downloaded, int uploaded) {
        try {
            Map<String, Object> params = new HashMap<>();
            int bytesLeft = this.length - downloaded;

            // adding all request parameters
            params.put("info_hash", this.encodeHash(this.infoHash));
            params.put("peer_id", this.peerId);
            params.put("port", 6881);
            params.put("uploaded", uploaded);
            params.put("downloaded", downloaded);
            params.put("left", bytesLeft);
            params.put("compact", 1);

            URL url = new URL(this.announceUrl + "?" + getReqString(params));
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Transmission/3.0.0");
            int resCode = conn.getResponseCode();
            return resCode;
        } catch (MalformedURLException e) {
            System.out.println("Bad URL in ping tracker");
            e.printStackTrace();
        } catch (IOException e) {
            System.out.println("IOException in ping tracker");
            e.printStackTrace();
        }
        return -1;
    }

    @SuppressWarnings("unchecked")
    public int completeSession() {
        try {
            Map<String, Object> params = new HashMap<>();
            int length = this.length;

            // adding all request parameters
            params.put("info_hash", this.encodeHash(this.infoHash));
            params.put("peer_id", this.peerId);
            params.put("port", 6881);
            params.put("event", "completed");
            params.put("uploaded", length);
            params.put("downloaded", length);
            params.put("left", 0);
            params.put("compact", 1);

            URL url = new URL(this.announceUrl + "?" + getReqString(params));
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Transmission/3.0.0");
            int resCode = conn.getResponseCode();
            return resCode;
        } catch (MalformedURLException e) {
            System.out.println("Bad URL in complete session");
            e.printStackTrace();
        } catch (IOException e) {
            System.out.println("IOException in complete session");
            e.printStackTrace();
        }
        return -1;
    }
}