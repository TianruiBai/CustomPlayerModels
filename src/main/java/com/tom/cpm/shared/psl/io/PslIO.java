package com.tom.cpm.shared.psl.io;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.tom.cpm.shared.io.IOHelper;
import com.tom.cpm.shared.psl.PslElement;
import com.tom.cpm.shared.psl.PslElementType;
import com.tom.cpm.shared.psl.PslSystem;

/**
 * Serialization for PSL data in the .cpmmodel binary format.
 * Reads/writes the PSL block that is appended to the model file.
 */
public class PslIO {

	private PslIO() {
	}

	/**
	 * Serialize all PSL elements to a byte array.
	 * Format:
	 *   int elementCount
	 *   for each element:
	 *     byte typeOrdinal (PslElementType)
	 *     [element data via PslElement.write()]
	 */
	public static byte[] write(PslSystem system) throws IOException {
		if (system == null || system.isEmpty()) {
			return new byte[0];
		}

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		IOHelper out = new IOHelper(baos);

		List<PslElement> elements = system.getElements();
		out.writeVarInt(elements.size());

		for (PslElement elem : elements) {
			elem.write(out);
		}

		out.close();
		return baos.toByteArray();
	}

	/**
	 * Deserialize PSL elements from a byte array into the given PslSystem.
	 * If data is null or empty, the system is left unchanged.
	 */
	public static void read(PslSystem system, byte[] data) throws IOException {
		if (data == null || data.length == 0) {
			return;
		}

		ByteArrayInputStream bais = new ByteArrayInputStream(data);
		IOHelper in = new IOHelper(bais);

		int count = in.readVarInt();
		List<PslElement> elements = new ArrayList<>(count);

		for (int i = 0; i < count; i++) {
			int typeOrdinal = in.readVarInt();
			if (typeOrdinal < 0 || typeOrdinal >= PslElementType.VALUES.length) {
				throw new IOException("Invalid PSL element type ordinal: " + typeOrdinal);
			}
			PslElementType type = PslElementType.VALUES[typeOrdinal];
			PslElement elem = PslElement.read(in, type);
			elements.add(elem);
		}

		system.setElements(elements);
		system.markClean();
	}
}
