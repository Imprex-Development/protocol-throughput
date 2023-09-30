package dev.imprex.protocol;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import com.comphenix.protocol.AsynchronousManager;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.async.AsyncListenerHandler;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.injector.GamePhase;
import com.comphenix.protocol.reflect.StructureModifier;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class ThroughputTest extends JavaPlugin implements Listener {

	private static final Set<PacketType> PACKET_TYPES = List.of(
			PacketType.Play.Server.MAP_CHUNK,
			PacketType.Play.Server.CHUNK_BATCH_START,
			PacketType.Play.Server.CHUNK_BATCH_FINISHED,
			PacketType.Play.Client.CHUNK_BATCH_RECEIVED
		).stream().filter(PacketType::isSupported).collect(Collectors.toSet());
	
	private static final byte[] EMPTY_CHUNK = createEmptyChunk();

	private static byte[] createEmptySection(int blockId) {
		ByteBuf buffer = Unpooled.buffer(8);
		buffer.writeShort(blockId == 0 ? 0 : 4096);
		
		buffer.writeByte(0);
		ByteBufUtil.writeVarInt(buffer, blockId);
		ByteBufUtil.writeVarInt(buffer, 0);
		
		buffer.writeByte(0);
		ByteBufUtil.writeVarInt(buffer, 0);
		ByteBufUtil.writeVarInt(buffer, 0);
		
		return Arrays.copyOf(buffer.array(), buffer.writerIndex());
	}

	private static byte[] createEmptyChunk() {
		byte[] section = createEmptySection(0);
		
		byte[] chunk = new byte[section.length * 24];
		for (int i = 0; i < 24; i++) {
			section = createEmptySection(i < 8 ? 14 : 0);
			System.arraycopy(section, 0, chunk, section.length * i, section.length);
		}

		return chunk;
	}

	private final ThroughputPacketListener packetListener = this.new ThroughputPacketListener();
	
	private ProtocolManager protocolManager;
	private AsynchronousManager asynchronousManager;

	private AsyncListenerHandler asyncListenerHandler;
	private boolean usingSyncListener = true;

	@Override
	public void onEnable() {
		this.protocolManager = ProtocolLibrary.getProtocolManager();
		this.asynchronousManager = protocolManager.getAsynchronousManager();

		this.protocolManager.addPacketListener(packetListener);

		getServer().getPluginManager().registerEvents(this, this);
	}

	@Override
	public void onDisable() {
		for (Player player : Bukkit.getOnlinePlayers()) {
			player.kickPlayer("plugin got disabled, please reconnect");
		}
	}

	@EventHandler
	public void onPlayerJoin(PlayerJoinEvent event) {
		event.getPlayer().sendMessage(getListenerMessage());
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (!command.getName().equalsIgnoreCase("throughput")) {
			return false;
		}

		if (this.usingSyncListener) {
			// remove sync listener
			this.protocolManager.removePacketListener(this.packetListener);

			// add and start async listener
			this.asyncListenerHandler = this.asynchronousManager.registerAsyncHandler(this.packetListener);
			this.asyncListenerHandler.start();

			// set listener type
			this.usingSyncListener = false;
		} else {
			// stop and remove async listener
			this.asyncListenerHandler.stop();
			this.asynchronousManager.unregisterAsyncHandler(this.asyncListenerHandler);

			// add sync listener
			this.protocolManager.addPacketListener(this.packetListener);

			// set listener type
			this.usingSyncListener = true;
		}

		for (Player player : Bukkit.getOnlinePlayers()) {
			player.kickPlayer(this.usingSyncListener
					? "changed packet listener to sync, please reconnect"
					: "changed packet listener to async, please reconnect");
		}

		return true;
	}

	private String getListenerMessage() {
		return this.usingSyncListener
				? "currently using sync packet listener"
				: "currently using async packet listener";
	}

	private class ThroughputPacketListener extends PacketAdapter {

		public ThroughputPacketListener() {
			super(PacketAdapter.params()
					.gamePhase(GamePhase.PLAYING)
					.plugin(ThroughputTest.this)
					.types(PACKET_TYPES));
		}

		@Override
		public void onPacketReceiving(PacketEvent event) {
			PacketContainer packet = event.getPacket();
			StructureModifier<Float> floats = packet.getFloat();

			// send chunks per tick of CHUNK_BATCH_RECEIVED
			event.getPlayer().sendMessage("chunks per tick: " + floats.read(0));
		}

		@Override
		public void onPacketSending(PacketEvent event) {
			// uncomment to check if sync and asnyc listener behave the same with same sending method
//			event.setCancelled(true);

			// uncomment for empty chunk to get higher chunk per tick value and difference
//			if (event.getPacketType() == PacketType.Play.Server.MAP_CHUNK) {
//				event.getPacket().getLevelChunkData().read(0).setBuffer(EMPTY_CHUNK);
//			}

			// uncomment to check if sync and asnyc listener behave the same with same sending method
//			Bukkit.getScheduler().runTask(plugin, () -> {
//				protocolManager.sendServerPacket(event.getPlayer(), event.getPacket(), false);
//			});
		}
	}
}
