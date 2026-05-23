package com.tom.cpm.shared.loaders;

import java.io.IOException;
import java.net.URL;

import com.tom.cpm.shared.definition.ModelDefinition;
import com.tom.cpm.shared.config.ResourceLoader;
import com.tom.cpm.shared.MinecraftClientAccess;
import com.tom.cpm.shared.network.NetHandler;

/**
 * Loads model definitions from the CPM built-in server database.
 * Registered under the "cpmdb" scheme: {@code cpmdb:{modelId}}.
 * 
 * When a model link references "cpmdb", this loader requests the model
 * from the current Minecraft server via ModelDownloadReqC2S/ChunkS2C.
 * The model data is AES-256-GCM encrypted during transport.
 */
public class CpmDbResourceLoader extends HttpResourceLoader {

	@Override
	protected URL createURL(String path) throws IOException {
		// cpmdb models are not fetched via URL — they come through the MC native port.
		// This method is only called by the parent class's loadResource(URL, ...) path.
		// We override loadResource(String, ...) directly instead.
		throw new IOException("cpmdb:// cannot be resolved via HTTP — use native port");
	}

	@Override
	public byte[] loadResource(String path, ResourceEncoding enc, ModelDefinition def) throws IOException {
		// path is the model ID string, e.g. "42" or "123"
		long modelId;
		try {
			modelId = Long.parseLong(path);
		} catch (NumberFormatException e) {
			throw new IOException("Invalid cpmdb model ID: " + path, e);
		}

		// Request model download from the server via native port
		NetHandler<?, ?, ?> netHandler = MinecraftClientAccess.get().getNetHandler();
		if (netHandler == null || !netHandler.hasModClient()) {
			throw new IOException("Cannot load cpmdb model: not connected to a CPM server");
		}

		// The actual model data will be delivered asynchronously via
		// ModelDownloadChunkS2C packets. This method returns the data
		// synchronously by blocking on the response.
		// 
		// In practice, the ModelDefinitionLoader calls loadResource() from
		// a background thread (THREAD_POOL), so blocking is acceptable.
		return netHandler.requestModelDownloadSync(modelId);
	}

	@Override
	public Validator getValidator() {
		return new Validator(
			"CPM Server DB",
			"cpmdb.local",
			"cpmdb\\:(\\d+)",
			"cpmdb:$1",
			"cpmdb:42");
	}
}
