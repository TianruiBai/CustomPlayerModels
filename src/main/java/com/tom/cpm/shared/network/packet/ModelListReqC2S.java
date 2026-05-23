package com.tom.cpm.shared.network.packet;

import java.io.IOException;

import com.tom.cpm.shared.io.IOHelper;
import com.tom.cpm.shared.network.IC2SPacket;
import com.tom.cpm.shared.network.NetH.ServerNetH;
import com.tom.cpm.shared.network.NetHandler;

/** C→S: Request model list for the authenticated player. */
public class ModelListReqC2S implements IC2SPacket {

	@Override
	public void read(IOHelper pb) throws IOException {
		pb.readByte(); // reserved flags
	}

	@Override
	public void write(IOHelper pb) throws IOException {
		pb.writeByte(0);
	}

	@Override
	public <P> void handle(NetHandler<?, P, ?> handler, ServerNetH net, P player) {
		// Dispatched by CpmModelPacketHandler
	}
}
