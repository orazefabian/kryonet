package com.esotericsoftware.kryonet;

import org.junit.jupiter.api.extension.*;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Timer;
import java.util.TimerTask;

import static org.junit.jupiter.api.Assertions.fail;

public class KryonetExtension implements ParameterResolver {

	@Override
	public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
		return parameterContext.getParameter().getType().equals(Kryonet.class);
	}

	@Override
	public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
		return new Kryonet();
	}

	public static class Kryonet {
		public final int tcpPort = 0;
		public final int udpPort = 0;

		static public String host = "localhost";


		private final ArrayList<Thread> threads = new ArrayList<>();
		ArrayList<EndPoint> endPoints = new ArrayList<>();
		boolean fail;
		private final Timer timer = new Timer();

		public void startEndPoint(EndPoint endPoint) {
			endPoints.add(endPoint);
			Thread thread = new Thread(endPoint, endPoint.getClass().getSimpleName());
			threads.add(thread);
			thread.start();
		}

		public void waitForThreads(int stopAfterMillis) {
			if (stopAfterMillis > 10000) throw new IllegalArgumentException("stopAfterMillis must be < 10000");
			stopEndPoints(stopAfterMillis);
			waitForThreads();
		}

		public void stopEndPoints(int stopAfterMillis) {
			timer.schedule(new TimerTask() {
				public void run() {
					for (EndPoint endPoint : endPoints)
						endPoint.stop();
					endPoints.clear();
				}
			}, stopAfterMillis);
		}

		public void waitForThreads() {
			fail = false;
			TimerTask failTask = new TimerTask() {
				public void run() {
					stopEndPoints();
					fail = true;
				}
			};
			timer.schedule(failTask, 11000);
			while (true) {
				for (Iterator iter = threads.iterator(); iter.hasNext(); ) {
					Thread thread = (Thread) iter.next();
					if (!thread.isAlive()) iter.remove();
				}
				if (threads.isEmpty()) break;
				try {
					Thread.sleep(100);
				} catch (InterruptedException ignored) {
				}
			}
			failTask.cancel();
			if (fail) fail("Test did not complete in a timely manner.");
			// Give sockets a chance to close before starting the next test.
			try {
				Thread.sleep(1000);
			} catch (InterruptedException ignored) {
			}
		}

		public void stopEndPoints() {
			stopEndPoints(0);
		}

	}
}
