package servers.components;

import java.util.concurrent.locks.Lock;

public interface ServerSynchronization {
	public Lock getClientsLock();
}
