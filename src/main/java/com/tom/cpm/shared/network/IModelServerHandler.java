package com.tom.cpm.shared.network;

import com.tom.cpl.nbt.NBTTagCompound;
import com.tom.cpm.shared.network.NetH.ServerNetH;

/**
 * Server-side dispatch interface for CPM model server packets.
 * Implemented by {@code CpmModelPacketHandler} in the server package.
 * All methods are called from C2S packet handle() implementations.
 */
public interface IModelServerHandler {

    /** Handle model list request. */
    <P> void handleModelList(NetHandler<?, P, ?> handler, ServerNetH net, P player);

    /** Handle upload initiation. Tag contains: name, desc, totalSize, numChunks, sha256. */
    <P> void handleUploadInit(NetHandler<?, P, ?> handler, ServerNetH net, P player, NBTTagCompound tag);

    /** Handle a data chunk. Tag contains: uploadId, chunkIdx, totalChunks, data, iv, tag, chunkSha256, fullSha256. */
    <P> void handleDataChunk(NetHandler<?, P, ?> handler, ServerNetH net, P player, NBTTagCompound tag);

    /** Handle upload completion. Tag contains: uploadId, fullSha256. */
    <P> void handleUploadComplete(NetHandler<?, P, ?> handler, ServerNetH net, P player, NBTTagCompound tag);

    /** Handle upload cancellation. Tag contains: uploadId. */
    <P> void handleUploadCancel(NetHandler<?, P, ?> handler, ServerNetH net, P player, NBTTagCompound tag);

    /** Handle upload resume request. Tag contains: uploadId. */
    <P> void handleUploadResume(NetHandler<?, P, ?> handler, ServerNetH net, P player, NBTTagCompound tag);

    /** Handle model download request. Tag contains: modelId. */
    <P> void handleDownload(NetHandler<?, P, ?> handler, ServerNetH net, P player, NBTTagCompound tag);

    /** Handle set active model. Tag contains: modelId. */
    <P> void handleSetActive(NetHandler<?, P, ?> handler, ServerNetH net, P player, NBTTagCompound tag);

    /** Handle set default model. Tag contains: modelId. */
    <P> void handleSetDefault(NetHandler<?, P, ?> handler, ServerNetH net, P player, NBTTagCompound tag);

    /** Handle model deletion. Tag contains: modelId. */
    <P> void handleDelete(NetHandler<?, P, ?> handler, ServerNetH net, P player, NBTTagCompound tag);
}
