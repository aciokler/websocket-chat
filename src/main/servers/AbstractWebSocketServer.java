package servers;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import servers.components.AbstractTimeoutThread;
import servers.components.AbstractWebSocketClientInstance;
import servers.components.ServerSynchronization;

public abstract class AbstractWebSocketServer<T extends AbstractWebSocketClientInstance>
		implements ServerSynchronization {

	protected int port;
	protected final int DEFAULT_TIMEOUT_MINUTES = 5;
	protected Map<Long, T> clients = new HashMap<>();
	protected TimeoutThread timeoutthread = new TimeoutThread(DEFAULT_TIMEOUT_MINUTES);
	protected ServerSocket serverSocket = null;

	// clients lock for synchronization
	protected Lock lock = new ReentrantLock();

	public AbstractWebSocketServer(int port) {
		this.port = port;
	}

	@Override
	public Lock getClientsLock() {
		return lock;
	}

	/**
	 * Perform any initialization before starting the server.
	 * 
	 * @return boolean - true if successfully initialized, false otherwise.
	 */
	protected abstract boolean initBeforeServerStart();

	protected abstract T createClient(Socket connection);

	protected void start() {
		try {
			System.out.println("starting server...");
			serverSocket = new ServerSocket(port);
			System.out.println("Server started!");

			// initialize the scanner session....
			if (!initBeforeServerStart()) {
				System.out.println("Error: Could not initialize the scanner session.");
				return;
			}

			timeoutthread.start();

			Runtime.getRuntime().addShutdownHook(new Thread() {
				@Override
				public void run() {

					System.out.println("shutting down this thing!.");

					timeoutthread.setEndTimeoutThread(true);
					cleanUp();
				}
			});

			while (true) {
				Socket newConnection = serverSocket.accept();
				System.out.println("new user connected " + newConnection.getInetAddress().getHostAddress());

				// create client thread...
				T thread = createClient(newConnection);
				thread.start();
				addClientInstance(thread);
			}
		} catch (IOException e) {
			e.printStackTrace();
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			System.out.println("finally is called...");
			cleanUp();
		}
	}

	protected void addClientInstance(T thread) {
		getClientsLock().lock();
		clients.put(thread.getId(), thread);
		getClientsLock().unlock();
	}

	protected void removeClientInstance(T thread) {
		getClientsLock().lock();
		clients.remove(thread.getId());
		getClientsLock().unlock();
	}

	protected void closeServerSocket() {
		try {
			serverSocket.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	protected void cleanUp() {
		System.out.println("server clean up");
		closeServerSocket();
		timeoutthread.setEndTimeoutThread(true);
	}

	protected class TimeoutThread extends AbstractTimeoutThread<T> {

		public TimeoutThread(int minutes) {
			super(minutes);
		}

		@Override
		public Lock getClientsLock() {
			return lock;
		}

		@Override
		protected Map<Long, T> getClients() {
			return clients;
		}
	}
}
