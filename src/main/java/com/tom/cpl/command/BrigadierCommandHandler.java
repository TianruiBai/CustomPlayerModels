package com.tom.cpl.command;

import java.util.Collections;
import java.util.Locale;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

public abstract class BrigadierCommandHandler<S> extends BrigadierCommandHandlerBase<S> {
	private final CommandDispatcher<S> dispatcher;

	@Deprecated
	public BrigadierCommandHandler(CommandDispatcher<S> dispatcher, boolean client) {
		this(dispatcher);
		register(client);
	}

	public BrigadierCommandHandler(CommandDispatcher<S> dispatcher) {
		this.dispatcher = dispatcher;
	}

	public void register(boolean client) {
		if (client)registerClient();
		else registerCommon();
	}

	protected abstract boolean hasOPPermission(S source);

	@Override
	public void register(LiteralCommandBuilder builder, boolean isOp) {
		LiteralArgumentBuilder<S> cmd = literal(builder.getName());
		if(isOp)cmd.requires(this::hasOPPermission);
		build(cmd, builder, Collections.emptyList(), cmd.getLiteral().toLowerCase(Locale.ROOT));
		dispatcher.register(cmd);
	}
}
