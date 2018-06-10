package servers;

import java.net.Socket;

import servers.ChatServer.ClientInstance;
import servers.components.AbstractWebSocketClientInstance;

public class ChatServer extends AbstractWebSocketServer<ClientInstance> {

	public ChatServer(int port) {
		super(port);
	}

	public void sendToAllClients(final String message) {
		System.out.println("client.values(): " + clients.values());
		ChatServer.this.getClientsLock().lock();
		for (ClientInstance client : clients.values()) {
			System.out.println("adding message to: " + client.getId());
			client.addMessage(message);
		}
		ChatServer.this.getClientsLock().unlock();
	}

	protected class ClientInstance extends AbstractWebSocketClientInstance {

		public ClientInstance(Socket connection) {
			super(connection);
		}

		@Override
		protected void removeClientInstance() {
			// ChatServer.this.removeClientInstance(this);
		}

		public void addMessage(String message) {
			byte[] byteMessage = prepareMessageFragment(message, OPCODES.TEXT_FINAL);
			BinaryMessage bMessage = new BinaryMessage(byteMessage);
			this.addMessageInQueue(bMessage);
		}

		public void add64EncodedMessage(byte[] arr) {
			String base64Encoded = base64Encode(arr);
			byte[] byteMessage = prepareMessageFragment(base64Encoded, OPCODES.TEXT_FINAL);
			BinaryMessage bMessage = new BinaryMessage(byteMessage);
			this.addMessageInQueue(bMessage);
		}

		@Override
		protected ReadingThread getReadingThreadInstance() {
			return new ChatReadingThread(connectionInfo);
		}

		protected class ChatReadingThread extends AbstractWebSocketClientInstance.ReadingThread {

			public ChatReadingThread(ConnectionInfo connection) {
				super(connection);
			}

			@Override
			protected void processMessage(final String message) {
				System.out.println("processing message: " + message);
				ChatServer.this.sendToAllClients(message);
			}
		}
	}

	@Override
	protected boolean initBeforeServerStart() {
		return true;
	}

	@Override
	protected ClientInstance createClient(Socket connection) {
		return new ClientInstance(connection);
	}

	public static void main(String[] args) {

		final ChatServer chat = new ChatServer(5353);

		try {
			chat.start();
		} finally {
			chat.cleanUp();
		}
	}

}
