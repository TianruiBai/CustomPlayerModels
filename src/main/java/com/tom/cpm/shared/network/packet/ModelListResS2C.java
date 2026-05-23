package com.tom.cpm.shared.network.packet;

import java.io.IOException;

import com.tom.cpl.nbt.NBTTagCompound;
import com.tom.cpm.shared.io.IOHelper;
import com.tom.cpm.shared.network.IS2CPacket;
import com.tom.cpm.shared.network.NetH;
import com.tom.cpm.shared.network.NetHandler;

/** S→C: Model list response. */
public class ModelListResS2C extends NBTS2C implements IS2CPacket {

	public ModelListResS2C() {}

	public ModelListResS2C(NBTTagCompound data) {
		super(data);
	}

	@Override
	public void read(IOHelper pb) throws IOException {
		tag = pb.readNBT();
	}

	@Override
	public void write(IOHelper pb) throws IOException {
		pb.writeNBT(tag);
	}

	@Override
	public void handle(NetHandler<?, ?, ?> handler, NetH from) {
		// Dispatched by client-side transfer client
	}
}
