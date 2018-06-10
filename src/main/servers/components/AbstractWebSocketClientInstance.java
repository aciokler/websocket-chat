package servers.components;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedList;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 
 * @author aciokler4
 * 
 */
public abstract class AbstractWebSocketClientInstance extends Thread {

	protected Lock lock = new ReentrantLock();

	protected ConnectionInfo connectionInfo = new ConnectionInfo();
	protected LinkedList<BinaryMessage> messageQueue = new LinkedList<>();

	protected ReadingThread readingThread;

	protected class BinaryMessage {

		private byte[] message;

		public BinaryMessage(byte[] message) {
			this.message = message;
		}

		public byte[] getByteArray() {
			return message;
		}
	}

	protected class ConnectionInfo {
		protected Socket connection;
		protected BufferedReader br = null;
		protected BufferedWriter bw = null;
		protected DataInputStream in = null;
		protected DataOutputStream out = null;

		protected boolean disconnect = false;
		protected long activityTimestamp;

		// public Socket getConnection() {
		// return connection;
		// }

		public void setConnection(Socket connection) {
			this.connection = connection;
		}

		public void setupIOStreams() throws IOException {
			// handle String messages with HTTP handshake requests and
			// responses...
			br = new BufferedReader(new InputStreamReader(connection.getInputStream()));
			bw = new BufferedWriter(new OutputStreamWriter(connection.getOutputStream()));

			// handle binary messages
			in = new DataInputStream(connection.getInputStream());
			out = new DataOutputStream(connection.getOutputStream());
		}

		public DataInputStream getInputStream() {
			return in;
		}

		public DataOutputStream getOutputStream() {
			return out;
		}

		public boolean disconnect() throws IOException {

			bw.close();
			br.close();
			out.close();
			in.close();
			connection.close();

			return true;
		}

		public void updateActivityTimeStamp() {
			lock.lock();
			activityTimestamp = System.currentTimeMillis();
			lock.unlock();
		}

		public long getActivityTimestamp() {
			lock.lock();
			long copy = activityTimestamp;
			lock.unlock();
			return copy;
		}

		public synchronized void disconnectClient() {
			System.out.println("disconnecting...");
			lock.lock();
			disconnect = true;
			lock.unlock();
			System.out.println("after disconnecting...");
		}

		public boolean getDisconnectClient() {
			lock.lock();
			boolean copy = disconnect;
			lock.unlock();
			return copy;
		}

		public void shutdownInput() throws IOException {
			connection.shutdownInput();
		}

		public void shutdownOutput() throws IOException {
			connection.shutdownOutput();
		}

		protected boolean performHandShake() throws IOException {
			boolean successful = false;

			updateActivityTimeStamp(); // initial activity timestamp
			// System.out.println("calling this guy: " + this.getId() + ",
			// connected? " + connectionInfo.getConnection().isConnected());

			StringBuilder httpHeader = new StringBuilder();
			while (connection.isConnected()) {
				if (getDisconnectClient()) // client got disconnected...end
											// thread
					break;

				String line = br.readLine();
				updateActivityTimeStamp(); // update time stamp
				if (line == null || line.isEmpty()) {
					System.out.println("line is empty");
					break;
				}

				httpHeader.append(line).append("\n");
			}

			String keyHeader = "Sec-WebSocket-Key: ";
			int start = httpHeader.indexOf(keyHeader);
			int end = httpHeader.indexOf("\n", start);
			String key = httpHeader.substring(start + keyHeader.length(), end);
			String acceptanceValue = encryptWithSHA1(key);

			// finish handshake
			bw.write("HTTP/1.1 101 Switching Protocols\r\n");
			bw.write("Upgrade: websocket\r\n");
			bw.write("Connection: Upgrade\r\n");
			bw.write("Sec-WebSocket-Accept: " + acceptanceValue + "\r\n");
			bw.write("\r\n");
			bw.flush();

			successful = true;

			return successful;
		}
	}

	public static enum OPCODES {
		TEXT_FINAL(0x81, 0x81),
		// TEXT_CONTINUES(0x01),
		PING(0x89, 0x09), PONG(0x8A, 0x0A), CLOSE(0x88, 0x08);

		private int opcode;
		private int mask;

		private OPCODES(final int opcode, final int mask) {
			this.opcode = opcode;
			this.mask = mask;
		}

		public static OPCODES resolveOpcode(byte[] buf) {
			char ch = (char) (buf[0] + 256);
			for (OPCODES op : OPCODES.values())
				if ((ch & op.getMask()) == op.getMask())
					return op;

			return null;
		}

		public int getOpcode() {
			return opcode;
		}

		public int getMask() {
			return mask;
		}
	}

	public AbstractWebSocketClientInstance(Socket connection) {
		this.connectionInfo.setConnection(connection);
	}

	private char[] convertBuffer(byte[] buf, final int lengthRead) {
		// browsers are typically implemented using C++ socket apis and it
		// doesn't
		// mix well with java's automatically converting to 2's complement.
		// Hence, to compensate for java's automagical 2's complement, we must
		// add 256 value to the received value.
		char[] convertedBuf = new char[lengthRead];
		for (int i = 0; i < lengthRead; i++)
			convertedBuf[i] = (char) (buf[i] + 256);
		return convertedBuf;
	}

	protected String decodeMessage(byte[] buf, final int lengthRead) {

		final char FIN_MESSAGE = 0x80;
		final char TEXT_MESSAGE = 0x01;
		final char MASKED_MESSAGE = 0x80;
		final char LENGTH_MASK = 0x7F;

		char[] convertedBuf = convertBuffer(buf, lengthRead);

		// parse data fragment
		if ((convertedBuf[0] & FIN_MESSAGE) != FIN_MESSAGE)
			System.out.println("sinding multiple fragments. Must read the rest...");

		if ((convertedBuf[0] & TEXT_MESSAGE) != TEXT_MESSAGE)
			System.out.println("Message is not text...only text messages supported.");

		if ((convertedBuf[1] & MASKED_MESSAGE) != MASKED_MESSAGE)
			System.out.println("Message is not masked. Client messages must be masked. Connection will be closed.");

		long lengthOfMessage = convertedBuf[1] & LENGTH_MASK;
		System.out.println("lengthOfMessage " + lengthOfMessage);
		int startByteOfMask = 2;

		if (lengthOfMessage <= 125)
			startByteOfMask = 2;
		else if (lengthOfMessage == 126) {
			startByteOfMask += 2;
			lengthOfMessage = 0; // reset length
		} else if (lengthOfMessage == 127) {
			startByteOfMask += 8;
			lengthOfMessage = 0; // reset length
		}
		int endByteOfMask = startByteOfMask + 4;

		for (int i = 2; i < startByteOfMask; i++)
			lengthOfMessage = (lengthOfMessage << 8) | (convertedBuf[i] & 0x00FF);

		System.out.println("final length: " + lengthOfMessage);

		// get the decoding mask
		char[] mask = new char[4];
		for (int i = startByteOfMask, j = 0; i < endByteOfMask; i++, j++)
			mask[j] = convertedBuf[i];

		// decode the message
		StringBuilder sb = new StringBuilder();
		for (int i = endByteOfMask, j = 0; j < lengthOfMessage; i++, j++)
			sb.append((char) (convertedBuf[i] ^ mask[j % 4]));

		System.out.println("final decoded message: " + sb);

		return sb.toString();
	}

	protected void decodeCloseNoEncodingTest(byte[] convertedBuf) {

		final char LENGTH_MASK = 0x7F;

		long lengthOfMessage = convertedBuf[1] & LENGTH_MASK;
		System.out.println("lengthOfMessage (CHECK) " + lengthOfMessage);
		if (lengthOfMessage <= 0)
			return;

		int startByteOfPayload = 2;

		if (lengthOfMessage <= 125)
			startByteOfPayload = 2;
		else if (lengthOfMessage == 126) {
			startByteOfPayload += 2;
			lengthOfMessage = 0; // reset length
		} else if (lengthOfMessage == 127) {
			startByteOfPayload += 8;
			lengthOfMessage = 0; // reset length
		}

		for (int i = 2; i < startByteOfPayload; i++)
			lengthOfMessage = (lengthOfMessage << 8) | (convertedBuf[i] & 0x00FF);

		System.out.println("final length: " + lengthOfMessage);

		int reasonCode = ((convertedBuf[startByteOfPayload] << 8) & 0xFF00)
				| (convertedBuf[startByteOfPayload + 1] & 0x00FF);

		System.out.println("reason code (CHECK): " + reasonCode);

		StringBuilder sb = new StringBuilder();
		for (int i = startByteOfPayload + 2, j = 2; j < lengthOfMessage; i++, j++)
			sb.append((char) convertedBuf[i]);

		System.out.println("reason: " + sb);
	}

	protected BinaryMessage decodeCloseFrame(byte[] buf, final int lengthRead) {

		final char MASKED_MESSAGE = 0x80;
		final char LENGTH_MASK = 0x7F;

		char[] convertedBuf = convertBuffer(buf, lengthRead);

		// parse data fragment
		if ((convertedBuf[1] & MASKED_MESSAGE) != MASKED_MESSAGE)
			System.out.println("Message is not masked. Client messages must be masked. Connection will be closed.");

		long lengthOfMessage = convertedBuf[1] & LENGTH_MASK;
		System.out.println("lengthOfMessage " + lengthOfMessage);
		if (lengthOfMessage <= 0) {
			// decodeCloseNoEncodingTest(convertedBuf);
			return null;
		}

		int startByteOfMask = 2;

		if (lengthOfMessage <= 125)
			startByteOfMask = 2;
		else if (lengthOfMessage == 126) {
			startByteOfMask += 2;
			lengthOfMessage = 0; // reset length
		} else if (lengthOfMessage == 127) {
			startByteOfMask += 8;
			lengthOfMessage = 0; // reset length
		}
		int endByteOfMask = startByteOfMask + 4;

		for (int i = 2; i < startByteOfMask; i++)
			lengthOfMessage = (lengthOfMessage << 8) | (convertedBuf[i] & 0x00FF);

		// get the decoding mask
		char[] mask = new char[4];
		for (int i = startByteOfMask, j = 0; i < endByteOfMask; i++, j++)
			mask[j] = convertedBuf[i];

		// decode the message
		for (int i = endByteOfMask, j = 0; j < lengthOfMessage; i++, j++)
			convertedBuf[i] = (char) (convertedBuf[i] ^ mask[j % 4]);

		int reasonCode = (convertedBuf[endByteOfMask] << 8) | convertedBuf[endByteOfMask + 1];

		System.out.println("reason code: " + reasonCode);

		StringBuilder sb = new StringBuilder();
		for (int i = endByteOfMask + 2, j = 2; j < lengthOfMessage; i++, j++)
			sb.append(convertedBuf[i]);

		System.out.println("reason: " + sb);

		convertedBuf[1] = (char) (convertedBuf[1] & 0x7F); // make sure the mask
															// bit is not set

		// decodeCloseNoEncodingTest(convertedBuf);

		// byte[] message = new byte[convertedBuf.length];
		// for(int i = 0; i < convertedBuf.length; i++)
		// message[i] = (byte) convertedBuf[i];

		byte[] message = prepareCloseFragment(sb.toString(), reasonCode);
		decodeCloseNoEncodingTest(message);

		BinaryMessage msg = new BinaryMessage(message);

		return msg;
	}

	protected byte[] prepareCloseFragment(final String closeMessage, final int reasonCode) {
		final int LENGTH_OF_REASON_CODE = 2;

		int closeMessageLength = closeMessage.length() + LENGTH_OF_REASON_CODE;
		int lengthOfMessage = closeMessageLength;
		lengthOfMessage += 2;
		// if(closeMessage.length() > 125)
		// lengthOfMessage += 2;

		int startOfData = 2;
		byte[] messageBytes = null;

		if (closeMessageLength > 125) {
			if (closeMessageLength < 65536) {
				System.out.println("entering 1st part. message: " + closeMessage);
				lengthOfMessage += 2;
				messageBytes = new byte[lengthOfMessage];
				messageBytes[1] = 126;
				startOfData += 2;
				messageBytes[2] = (byte) ((closeMessageLength & 0x0000FF00) >> 8);
				messageBytes[3] = (byte) ((closeMessageLength & 0x000000FF));
			} else
			// not implemented yet
			{
				System.out.println("entering second part. message: " + closeMessage);
				lengthOfMessage += 8;
				System.out.println("length of byte message: " + lengthOfMessage);
				messageBytes = new byte[lengthOfMessage];
				messageBytes[1] = 127;
				startOfData += 8;
				messageBytes[2] = (byte) (closeMessageLength & 0);
				messageBytes[3] = (byte) (closeMessageLength & 0);
				messageBytes[4] = (byte) (closeMessageLength & 0);
				messageBytes[5] = (byte) (closeMessageLength & 0);
				messageBytes[6] = (byte) ((closeMessageLength & 0xFF000000) >> 24);
				messageBytes[7] = (byte) ((closeMessageLength & 0x00FF0000) >> 16);
				messageBytes[8] = (byte) ((closeMessageLength & 0x0000FF00) >> 8);
				messageBytes[9] = (byte) (closeMessageLength & 0x000000FF);
			}
		} else {
			System.out.println("entering 3rd part. message: " + closeMessage);
			messageBytes = new byte[lengthOfMessage];
			messageBytes[1] = (byte) closeMessageLength;
		}
		messageBytes[0] = (byte) OPCODES.CLOSE.getOpcode();

		messageBytes[1] = (byte) (messageBytes[1] & 0x7F); // make sure the mask
															// bit is not set

		messageBytes[startOfData] = (byte) ((reasonCode & 0x0000FF00) >> 8);
		messageBytes[startOfData + 1] = (byte) (reasonCode & 0x000000FF);

		int reasonCodeTest = ((messageBytes[startOfData] << 8) & 0xFF00) | (messageBytes[startOfData + 1] & 0x00FF);
		System.out.println("reasonCodeTest: " + reasonCodeTest);

		for (int i = 0, j = startOfData + 2; i < closeMessage.length(); i++, j++)
			messageBytes[j] = (byte) closeMessage.charAt(i);

		return messageBytes;
	}

	protected byte[] prepareMessageFragment(final String message, final OPCODES opcode) {

		int lengthOfMessage = message.length();
		lengthOfMessage += 2;
		// if(message.length() > 125)
		// lengthOfMessage += 2;

		int startOfData = 2;
		byte[] messageBytes = null;

		if (message.length() > 125) {
			if (message.length() < 65536) {
				System.out.println("entering 1st part. message: " + message);
				lengthOfMessage += 2;
				messageBytes = new byte[lengthOfMessage];
				messageBytes[1] = 126;
				startOfData += 2;
				messageBytes[2] = (byte) ((message.length() & 0x0000FF00) >> 8);
				messageBytes[3] = (byte) ((message.length() & 0x000000FF));
			} else
			// not implemented yet
			{
				System.out.println("entering second part. message: " + message);
				lengthOfMessage += 8;
				System.out.println("length of byte message: " + lengthOfMessage);
				messageBytes = new byte[lengthOfMessage];
				messageBytes[1] = 127;
				startOfData += 8;
				messageBytes[2] = (byte) (message.length() & 0);
				messageBytes[3] = (byte) (message.length() & 0);
				messageBytes[4] = (byte) (message.length() & 0);
				messageBytes[5] = (byte) (message.length() & 0);
				messageBytes[6] = (byte) ((message.length() & 0xFF000000) >> 24);
				messageBytes[7] = (byte) ((message.length() & 0x00FF0000) >> 16);
				messageBytes[8] = (byte) ((message.length() & 0x0000FF00) >> 8);
				messageBytes[9] = (byte) (message.length() & 0x000000FF);
			}
		} else {
			System.out.println("entering 3rd part. message: " + message);
			messageBytes = new byte[lengthOfMessage];
			messageBytes[1] = (byte) message.length();
		}
		messageBytes[0] = (byte) opcode.getOpcode();

		for (int i = 0, j = startOfData; i < message.length(); i++, j++)
			messageBytes[j] = (byte) message.charAt(i);

		return messageBytes;
	}

	// protected void initIO() throws IOException {
	// connectionInfo.setupIOStreams();
	// }

	protected String encryptWithSHA1(String key) {

		String acceptanceKey = null;
		try {
			MessageDigest cypher = MessageDigest.getInstance("SHA-1");
			String finalKey = key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
			System.out.println("finalKey " + finalKey);
			cypher.update(finalKey.getBytes("UTF-8"), 0, finalKey.length());
			byte[] buf = cypher.digest();
			acceptanceKey = base64Encode(buf);
		} catch (UnsupportedEncodingException e) {

		} catch (NoSuchAlgorithmException e) {

		}

		return acceptanceKey;
	}

	protected String base64Encode(byte[] in) {
		final String codes = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=";
		StringBuffer out = new StringBuffer((in.length * 4) / 3);
		int b;
		for (int i = 0; i < in.length; i += 3) {
			b = (in[i] & 0xFC) >> 2;
			out.append(codes.charAt(b));
			b = (in[i] & 0x03) << 4;
			if (i + 1 < in.length) {
				b |= (in[i + 1] & 0xF0) >> 4;
				out.append(codes.charAt(b));
				b = (in[i + 1] & 0x0F) << 2;
				if (i + 2 < in.length) {
					b |= (in[i + 2] & 0xC0) >> 6;
					out.append(codes.charAt(b));
					b = in[i + 2] & 0x3F;
					out.append(codes.charAt(b));
				} else {
					out.append(codes.charAt(b));
					out.append('=');
				}
			} else {
				out.append(codes.charAt(b));
				out.append("==");
			}
		}

		return out.toString();
	}

	protected ReadingThread getReadingThreadInstance() {
		return new ReadingThread(connectionInfo);
	}

	@Override
	public void run() {
		System.out.println("running thread: " + this.getId());
		try {
			// initIO();
			connectionInfo.setupIOStreams();

			if (!connectionInfo.performHandShake())
				return;

			// spawn reading thread...
			readingThread = getReadingThreadInstance();
			readingThread.start();

			// continue with writing...
			performWrite();
		} catch (IOException e) {
			e.printStackTrace();
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			disconnect();
		}
	}

	protected class ReadingThread extends Thread {

		protected ConnectionInfo connection;
		protected boolean endReading = false;
		BinaryMessage closeMessage = null;

		public ReadingThread(ConnectionInfo connection) {
			this.connection = connection;
		}

		@Override
		public void run() {

			try {
				int bufLength = 4096;
				byte[] buf = new byte[bufLength];
				int length = 0;

				while (true) {
					if (connection.getDisconnectClient()) {
						System.out.println("breaking out of reading loop");
						break;
					}

					System.out.println("before reading...");
					length = connection.getInputStream().read(buf, 0, bufLength);
					System.out.println("after reading... : " + length);
					connection.updateActivityTimeStamp();
					if (length < 0) {
						// sleep(100);
						System.out.println("shutting down input");
						connectionInfo.shutdownInput();
						System.out.println("disconnecting client");
						connection.disconnectClient();
						continue;
					}

					handleIncomingMessage(buf, length);
				}
			} catch (IOException e) {
				e.printStackTrace();
			} finally {
				System.out.println("ending read thread...");
			}
		}

		protected void processMessage(final String message) {

		}

		private void handleIncomingMessage(byte[] buf, int readLength) throws IOException {
			OPCODES opcode = OPCODES.resolveOpcode(buf);
			if (opcode == null) {
				System.out.println("opcode not supported.");
				return;
			}

			BinaryMessage bMessage = null;
			switch (opcode) {
			case TEXT_FINAL:
				String message = decodeMessage(buf, readLength);
				// if("CLIENT:CLOSE".equals(message))
				// {
				// bMessage = new BinaryMessage(prepareCloseFragment("closing
				// normally.", 1000));
				// addMessageInQueue(bMessage);
				// // bMessage = new
				// BinaryMessage(prepareMessageFragment("Testing",
				// OPCODES.TEXT_FINAL));
				// // addMessageInQueue(bMessage);
				// bMessage = new BinaryMessage(prepareCloseFragment("closing
				// normally.", 1000));
				// addMessageInQueue(bMessage);
				// endReading = true;
				// }
				processMessage(message);

				System.out.println("text message from client: " + message);
				break;
			case PING:
				System.out.println("PING");
				bMessage = new BinaryMessage(prepareMessageFragment("Pong", OPCODES.PONG));
				addMessageInQueue(bMessage);
				break;
			case PONG:
				System.out.println("PONG");
				// IGNORE...
				break;
			case CLOSE:
				System.out.println("CLOSE MSG");

				// byte[] messageInBytes = new byte[readLength];
				// for(int i = 0; i < readLength; i++)
				// messageInBytes[i] = buf[i];

				closeMessage = decodeCloseFrame(buf, readLength);

				if (closeMessage == null) {
					byte[] arr = new byte[2];
					arr[0] = (byte) OPCODES.CLOSE.getOpcode();
					arr[1] = 0x00;
					closeMessage = new BinaryMessage(arr);
				}

				connectionInfo.getOutputStream().write(closeMessage.getByteArray());
				connectionInfo.getOutputStream().flush();
				// System.out.println("close message: " + closeMessage);
				// if(bMessage == null)
				// bMessage = new BinaryMessage(prepareCloseFragment("closing
				// normally.", 1000));
				// byte[] messageInBytes = prepareMessageFragment("CLOSE",
				// OPCODES.CLOSE);
				// bMessage = new BinaryMessage(messageInBytes);
				// addMessageInQueue(closeMessage);
				// if(!endReading)
				// {
				// System.out.println("adding message to queue " +
				// closeMessage);
				// bMessage = new
				// BinaryMessage(prepareMessageFragment("Testing",
				// OPCODES.TEXT_FINAL));
				// addMessageInQueue(bMessage);
				// addMessageInQueue(closeMessage);
				// }
				connection.disconnectClient();
				// sleep(2000);
				break;
			default:
				System.out.println("opcode not supported.");
			}
		}
	}

	protected void performWrite() throws IOException, InterruptedException {

		// connectionInfo.getOutputStream().write(prepareMessageFragment("hey",
		// OPCODES.PING));
		// connectionInfo.getOutputStream().flush();

		while (true) {
			// if(!getSendMessage())
			// {
			// sleep(100);
			// continue;
			// }
			// setSendMessage(false); // reset sendMessage flag

			// String sendMessage = fullName;// "This is a message from the
			// server";
			// System.out.println("sending message to a client: " +
			// sendMessage);
			// byte[] message = prepareMessageFragment(sendMessage);
			// out.write(message);
			// out.flush();

			// String base64Image = base64Encode(photoBytes);
			// message = prepareMessageFragment(base64Image);
			// out.write(message);
			// out.flush();
			BinaryMessage message = null;
			if (connectionInfo.getDisconnectClient()) {
				System.out.println("disconnecting and writing any last messages...");
				// flush all the messages waiting to be written to the client
				// before disconnecting...
				// while(true)
				// {
				// message = getMessageFromQueue();
				// System.out.println("insdie gettting message: " + message);
				// if(message == null)
				// break;
				//
				// connectionInfo.getOutputStream().write(message.getByteArray());
				// connectionInfo.getOutputStream().flush();
				// }

				// byte[] test = prepareCloseFragment("Close normally", 1000);
				// connectionInfo.getOutputStream().write(test);
				// connectionInfo.getOutputStream().flush();

				// byte[] messsageInBytes = prepareCloseFragment("Closed
				// normally", 1000);
				// connectionInfo.getOutputStream().write(messsageInBytes);
				// connectionInfo.getOutputStream().flush();

				// System.out.println("shutting down output");
				// connectionInfo.shutdownOutput();
				// System.out.println("output shutdown");
				// connectionInfo.shutdownInput();

				// sleep(100);
				break;
			}

			message = getMessageFromQueue();
			// System.out.println("sending message 1: " + message);
			if (message == null) {
				sleep(500);
				continue;
			}

			System.out.println("sending message 2");
			connectionInfo.getOutputStream().write(message.getByteArray());
			connectionInfo.getOutputStream().flush();

			connectionInfo.updateActivityTimeStamp(); // TODO should I put this
														// here or before the
														// write?
		}
	}

	protected void disconnect() {
		System.out.println("disconnecting...");
		try {
			removeClientInstance();
			connectionInfo.disconnect();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public void requestDisconnect() {
		connectionInfo.disconnectClient();
	}

	public long getActivityTimeStamp() {
		return connectionInfo.getActivityTimestamp();
	}

	protected Lock getLock() {
		return lock;
	}

	protected LinkedList<BinaryMessage> getMessageQueue() {
		return messageQueue;
	}

	protected BinaryMessage getMessageFromQueue() {
		BinaryMessage msg = null;
		// System.out.println("get message from queue");
		getLock().lock();
		if (!getMessageQueue().isEmpty())
			msg = getMessageQueue().removeFirst();
		getLock().unlock();
		// System.out.println("after getting message from queue");
		return msg;
	}

	protected void addMessageInQueue(BinaryMessage message) {
		// System.out.println("1: " + (message == null) + ", 2: " +
		// (message.getByteArray() == null) + ", 3: " +
		// (message.getByteArray().length == 0));
		if (message == null || message.getByteArray() == null || message.getByteArray().length == 0)
			return;

		System.out.println("adding message in queue");
		getLock().lock();
		getMessageQueue().addLast(message);
		getLock().unlock();
		System.out.println("after adding message to quueue");
	}

	protected abstract void removeClientInstance();

}
