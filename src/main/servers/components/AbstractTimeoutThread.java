package servers.components;

import java.util.LinkedList;
import java.util.Map;

/**
 * 
 * @author aciokler4
 * 
 * @param <T>
 */
public abstract class AbstractTimeoutThread<T extends AbstractWebSocketClientInstance> extends Thread
		implements ServerSynchronization {

	private int timeoutMinutes;
	private boolean endTimeoutThread = false;

	public AbstractTimeoutThread(final int minutes) {
		timeoutMinutes = minutes;
	}

	public synchronized boolean getEndTimeoutThread() {
		return endTimeoutThread;
	}

	public synchronized void setEndTimeoutThread(boolean endThread) {
		endTimeoutThread = endThread;
	}

	protected abstract Map<Long, T> getClients();

	@Override
	public void run() {

		long timeoutMillies = timeoutMinutes * 60 * 1000;
		long minSleepTime = 1000;
		int timesToSleep = (int) (timeoutMillies / minSleepTime);

		try {
			T thread = null;
			LinkedList<Long> threadIdsTimedout = new LinkedList<>();
			while (true) {
				// sleep timeout thread untill it's ready to check.
				for (int i = 0; i < timesToSleep; i++) {
					if (getEndTimeoutThread()) // if thread is to be terminated,
												// then end run method
						return;

					sleep(minSleepTime);
				}

				// resume checking time out
				getClientsLock().lock();
				for (Map.Entry<Long, T> entry : getClients().entrySet()) {
					thread = entry.getValue();
					long delta = System.currentTimeMillis() - thread.getActivityTimeStamp();
					if (delta >= timeoutMillies)
						threadIdsTimedout.add(thread.getId());
				}

				System.out.println("TIMEOUTTHREAD: thread id timeout list: " + threadIdsTimedout);
				// disconnect the timedout threads...
				for (Long threadId : threadIdsTimedout) {
					thread = getClients().remove(threadId);
					thread.requestDisconnect();
				}
				getClientsLock().unlock();

				// reset state of list
				threadIdsTimedout.clear();
			}
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} finally {
			System.out.println("ending timeout thread...");
		}
	}
}