package com.tom.cpm.shared.network.packet;

import java.io.IOException;

import com.tom.cpm.shared.io.IOHelper;
import com.tom.cpm.shared.network.IS2CPacket;
import com.tom.cpm.shared.network.NetH;
import com.tom.cpm.shared.network.NetHandler;

/**
 * S→C: Time synchronization for time-bound encryption.
 * Sent when the client's clock has drifted beyond the acceptable skew.
 * Client should update its clock offset and retry the failed message.
 */
public class TimeSyncS2C implements IS2CPacket {
	private long serverTimeSeconds;
	private long timeWindowId;

	public TimeSyncS2C() {}

	public TimeSyncS2C(long serverTimeSeconds, long timeWindowId) {
		this.serverTimeSeconds = serverTimeSeconds;
		this.timeWindowId = timeWindowId;
	}

	@Override
	public void read(IOHelper pb) throws IOException {
		serverTimeSeconds = pb.readLong();
		timeWindowId = pb.readLong();
	}

	@Override
	public void write(IOHelper pb) throws IOException {
		pb.writeLong(serverTimeSeconds);
		pb.writeLong(timeWindowId);
	}

	@Override
	public void handle(NetHandler<?, ?, ?> handler, NetH from) {
		// Client adjusts its clock offset: offset = serverTimeSeconds - localTimeSeconds
		// Subsequent messages use corrected time for window ID calculation
	}

	public long getServerTimeSeconds() { return serverTimeSeconds; }
	public long getTimeWindowId() { return timeWindowId; }
}
