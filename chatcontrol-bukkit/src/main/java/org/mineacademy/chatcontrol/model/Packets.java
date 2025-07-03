package org.mineacademy.chatcontrol.model;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientTabComplete;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTabComplete;
import org.bukkit.entity.Player;
import org.mineacademy.chatcontrol.SenderCache;
import org.mineacademy.chatcontrol.settings.Settings;
import org.mineacademy.fo.CommonCore;
import org.mineacademy.fo.MinecraftVersion;
import org.mineacademy.fo.MinecraftVersion.V;
import org.mineacademy.fo.PlayerUtil;
import org.mineacademy.fo.annotation.AutoRegister;
import org.mineacademy.fo.collection.ExpiringMap;
import org.mineacademy.fo.model.PacketListener;
import org.mineacademy.fo.platform.FoundationPlayer;
import org.mineacademy.fo.platform.Platform;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;

/**
 * Represents packet handling using PacketEvents
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AutoRegister(hideIncompatibilityWarnings = true)
public final class Packets extends PacketListener {

	/**
	* The singleton for this class
	*/
	@Getter
	private static final Packets instance = new Packets();

	/**
	 * Connects tab-complete sending and receiving packet
	 */
	private final Map<String, String> buffers = ExpiringMap.builder().expiration(10, TimeUnit.MINUTES).build();

	/**
	 * Players being processed RIGHT NOW inside the method. Prevents dead loop.
	 */
	private final Set<String> playersPendingMessageRemoval = new HashSet<>();

	/**
	 * Register and initiate packet listening
	 */
	@Override
	public void onRegister() {

		if (!Settings.PacketEvents.ENABLED)
			return;

		//
		// Process tab-completions for legacy Minecraft versions
		//
		if (MinecraftVersion.olderThan(V.v1_13)) {

			// Receiving tab complete request
			this.addReceivingListener(PacketListenerPriority.HIGHEST, PacketType.Play.Client.TAB_COMPLETE, event -> {
				final WrapperPlayClientTabComplete packet = new WrapperPlayClientTabComplete(event);
				final String buffer = packet.getText();
				final Player player = event.getPlayer();

				// Save for sending later, see below
				this.buffers.put(player.getName(), buffer);
			});

			// Filter players from tab complete
			this.addSendingListener(PacketListenerPriority.HIGHEST, PacketType.Play.Server.TAB_COMPLETE, event -> {
				final Player player = event.getPlayer();
				final String buffer = this.buffers.remove(player.getName());

				if (buffer == null || player.hasPermission(Permissions.Bypass.TAB_COMPLETE))
					return;

				final boolean hasVanishBypass = player.hasPermission(Permissions.Bypass.VANISH);
				final WrapperPlayServerTabComplete packet = new WrapperPlayServerTabComplete(event);
				final List<WrapperPlayServerTabComplete.CommandMatch> suggestions = packet.getCommandMatches();
				final Set<String> nicks = new HashSet<>();
				final boolean isCommand = buffer.charAt(0) == '/';

				// Prevent tab completing completely if the command is too short
				if (isCommand && Settings.TabComplete.PREVENT_IF_BELOW_LENGTH != 0 && (buffer.length() - 1) < Settings.TabComplete.PREVENT_IF_BELOW_LENGTH) {
					event.setCancelled(true);

					return;
				}

				// Remove vanished players
				for (int i = 0; i < suggestions.size(); i++) {
					final WrapperPlayServerTabComplete.CommandMatch suggestion = suggestions.get(i);
					final String suggestionText = suggestion.getText();
					final Player suggestedPlayer = Players.findPlayer(suggestionText);

					if (suggestedPlayer != null) {
						if (hasVanishBypass || !PlayerUtil.isVanished(suggestedPlayer)) {
							final String nick = Settings.TabComplete.USE_NICKNAMES ? Players.getNickOrNullColorless(suggestedPlayer) : null;

							suggestions.add(new WrapperPlayServerTabComplete.CommandMatch(nick != null ? nick : suggestedPlayer.getName()));
						}

						suggestions.remove(suggestion);
					}

					else if (isCommand && !Settings.TabComplete.WHITELIST.isInListRegex(suggestionText))
						suggestions.remove(suggestion);
				}

				// Add all nicknames matching the word, ignoring commands
				if (!isCommand) {
					final String word = buffer.endsWith(" ") ? "" : CommonCore.last(buffer.split(" "));

					List<WrapperPlayServerTabComplete.CommandMatch> matches = CommonCore.tabComplete(word, Players.getPlayerNamesForTabComplete(hasVanishBypass))
							.stream()
							.map(WrapperPlayServerTabComplete.CommandMatch::new)
							.collect(Collectors.toList());
					suggestions.addAll(matches);
				}

				// Sort
				suggestions.sort(Comparator.comparing(WrapperPlayServerTabComplete.CommandMatch::getText, String.CASE_INSENSITIVE_ORDER));
				packet.setCommandMatches(suggestions);
			});
		}

		//
		// Process chat messages
		//
		this.addPacketListener(new SimpleChatAdapter() {

			@Override
			protected String onJsonMessage(final Player player, final String jsonMessage) {
				synchronized (Packets.this.playersPendingMessageRemoval) {
					if (!Packets.this.playersPendingMessageRemoval.contains(player.getName()))
						SenderCache.from(player).getLastChatPackets().add(jsonMessage);

					return jsonMessage;
				}
			}
		});
	}

	/**
	 * Remove the given message containing the given unique ID for all players,
	 * sending them their last 100 messages without it, or blank if not enough data
	 *
	 * Removal is depending on the given remove mode
	 *
	 * @param mode
	 * @param uniqueId
	 */
	public void removeMessage(final RemoveMode mode, final UUID uniqueId) {
		Platform.runTask(() -> {
			synchronized (this.playersPendingMessageRemoval) {
				final String stringId = uniqueId.toString();

				for (final Iterator<SenderCache> cacheIt = SenderCache.getCaches(); cacheIt.hasNext();) {
					final SenderCache cache = cacheIt.next();
					final List<String> last100Packets = new ArrayList<>(100);

					last100Packets.addAll(cache.getLastChatPackets());

					boolean found = false;

					for (final Iterator<String> it = last100Packets.iterator(); it.hasNext();) {
						final String jsonMessage = it.next();

						if (jsonMessage.contains(mode.getPrefix() + "_" + stringId)) {
							it.remove();

							found = true;
						}
					}

					if (found && !this.playersPendingMessageRemoval.contains(cache.getSenderName()))
						try {
							this.playersPendingMessageRemoval.add(cache.getSenderName());
							final List<String> new100packets = new ArrayList<>();

							// Fill in the blank if no data
							for (int i = 0; i < 100 - last100Packets.size(); i++)
								new100packets.add(0, "{\"text\": \" \"}");

							for (final String json : last100Packets)
								new100packets.add(json);

							final Player player = cache.toPlayer();

							if (player != null) {
								final FoundationPlayer audience = Platform.toPlayer(player);

								for (final String jsonPacket : new100packets)
									audience.sendJson(jsonPacket);
							}

							cache.getLastChatPackets().clear();
							cache.getLastChatPackets().addAll(new100packets);

						} finally {
							this.playersPendingMessageRemoval.remove(cache.getSenderName());
						}
				}
			}
		});
	}

	/**
	 * How we should remove sent messages?
	 */
	@RequiredArgsConstructor
	public enum RemoveMode {

		/**
		 * Only remove the message matching the UUID
		 */
		SPECIFIC_MESSAGE("SPECIFIC_MESSAGE", "flpm"),

		/**
		 * Remove all messages from the UUID of the sender
		 */
		ALL_MESSAGES_FROM_SENDER("ALL_MESSAGES_FROM_SENDER", "flps");

		/**
		 * The unobfuscatable key
		 */
		@Getter
		private final String key;

		/**
		 * The prefix used for matching in the method
		 */
		@Getter
		private final String prefix;

		/**
		 * @see java.lang.Enum#toString()
		 */
		@Override
		public String toString() {
			return this.key;
		}

		/**
		 * Parse from {@link #getKey()}
		 *
		 * @param key
		 * @return
		 */
		public static RemoveMode fromKey(final String key) {
			for (final RemoveMode mode : values())
				if (mode.getKey().equalsIgnoreCase(key))
					return mode;

			throw new IllegalArgumentException("No such packet remove mode " + key + " Available: " + values());
		}
	}
}
