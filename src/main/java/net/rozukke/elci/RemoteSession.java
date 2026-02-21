// Edited by rozukke from original file to add commands, remove unnecessary functionality and change naming

package net.rozukke.elci;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.entity.*;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.util.Vector;

import java.io.*;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Iterator;

import static java.nio.charset.StandardCharsets.UTF_8;

public class RemoteSession {

	private Location origin;

	private final Socket socket;

	private BufferedReader in;

	private BufferedWriter out;
	
	private Thread inThread;
	
	private Thread outThread;

	private final ArrayDeque<String> inQueue = new ArrayDeque<>();

	private final ArrayDeque<String> outQueue = new ArrayDeque<>();

	public boolean running = true;

	public boolean pendingRemoval = false;

	public ELCIPlugin plugin;

	protected ArrayDeque<PlayerInteractEvent> interactEventQueue = new ArrayDeque<>();
	
	protected ArrayDeque<AsyncPlayerChatEvent> chatPostedQueue = new ArrayDeque<>();
	
	protected ArrayDeque<ProjectileHitEvent> projectileHitQueue = new ArrayDeque<>();

	private final int maxCommandsPerTick = 9000;

	private boolean closed = false;

	private Player attachedPlayer = null;

	public RemoteSession(ELCIPlugin plugin, Socket socket) throws IOException {
		this.socket = socket;
		this.plugin = plugin;
		init();
	}

	public void init() throws IOException {
		socket.setTcpNoDelay(true);
		socket.setKeepAlive(true);
		socket.setTrafficClass(0x10);
		this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), UTF_8));
		this.out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), UTF_8));
		startThreads();
		plugin.getLogger().info("Opened connection to" + socket.getRemoteSocketAddress() + ".");
	}

	protected void startThreads() {
		inThread = new Thread(new InputThread());
		inThread.start();
		outThread = new Thread(new OutputThread());
		outThread.start();
	}


	public void setOrigin(Location origin) {
		this.origin = origin;
	}

	public void queueChatPostedEvent(AsyncPlayerChatEvent event) {
		//plugin.getLogger().info(event.toString());
		chatPostedQueue.add(event);
	}

	/** called from the server main thread */
	public void tick() {
		if (origin == null) {
			this.origin = new Location(plugin.getServer().getWorlds().get(0), 0, 0, 0);
			}
		int processedCount = 0;
		String message;
		while ((message = inQueue.poll()) != null) {
			handleLine(message);
			processedCount++;
			if (processedCount >= maxCommandsPerTick) {
				plugin.getLogger().warning("Over " + maxCommandsPerTick +
					" commands were queued - deferring " + inQueue.size() + " to next tick");
				break;
			}
		}

		if (!running && inQueue.size() == 0) {
			pendingRemoval = true;
		}
	}

	protected void handleLine(String line) {
//		System.out.println(line);
		String methodName = line.substring(0, line.indexOf("("));
		//split string into args, handles , inside " i.e. ","
		String[] args = line.substring(line.indexOf("(") + 1, line.length() - 1).split(",");
		//System.out.println(methodName + ":" + Arrays.toString(args));
		handleCommand(methodName, args);
	}

	protected void handleCommand(String c, String[] args) {
		
		try {
			// get the server
			Server server = plugin.getServer();
			
			// get the world
			World world = origin.getWorld();
			
			// world.getBlock
			switch (c) {
				case "testFailCommand": {
					send("Fail");
					break;
				}
				case "player.doCommand": {
					Player player = getCurrentPlayer();
					if (player==null) {
						break;
					}

					player.performCommand(String.join(",", args));
					break;
				}
				case "world.getBlock": {
					Location loc = parseRelativeBlockLocation(args[0], args[1], args[2]);
					Block block = world.getBlockAt(loc);
					send(block.getType().getId());
					// world.getBlocks
					break;
				}
				case "world.getBlocks": {
					Location loc1 = parseRelativeBlockLocation(args[0], args[1], args[2]);
					Location loc2 = parseRelativeBlockLocation(args[3], args[4], args[5]);
					send(getBlocks(loc1, loc2, false));
					// world.getBlockWithData
					break;
				}
				case "world.getBlockWithData": {
					Location loc = parseRelativeBlockLocation(args[0], args[1], args[2]);
					Block block = world.getBlockAt(loc);
					send(block.getType().getId() + "," + world.getBlockAt(loc).getData());

					// world.setBlock
					break;
				}
				case "world.getBlocksWithData": {
					Location loc1 = parseRelativeBlockLocation(args[0], args[1], args[2]);
					Location loc2 = parseRelativeBlockLocation(args[3], args[4], args[5]);
					send(getBlocks(loc1, loc2, true));
					break;
				}
				case "world.setBlock": {
					Location loc = parseRelativeBlockLocation(args[0], args[1], args[2]);
					updateBlock(world, loc, Integer.parseInt(args[3]), (args.length > 4 ? Byte.parseByte(args[4]) : (byte) 0));

					// world.setBlocks
					break;
				}
				case "world.setBlocks": {
					Location loc1 = parseRelativeBlockLocation(args[0], args[1], args[2]);
					Location loc2 = parseRelativeBlockLocation(args[3], args[4], args[5]);
					int blockType = Integer.parseInt(args[6]);
					byte data = args.length > 7 ? Byte.parseByte(args[7]) : (byte) 0;
					setCuboid(loc1, loc2, blockType, data);

					// world.getPlayerIds
					break;
				}
				case "world.getPlayerIds": {
					StringBuilder bdr = new StringBuilder();
					Collection<? extends Player> players = Bukkit.getOnlinePlayers();
					if (players.size() > 0) {
						for (Player p : players) {
							bdr.append(p.getEntityId());
							bdr.append("|");
						}
						bdr.deleteCharAt(bdr.length() - 1);
						send(bdr.toString());
					} else {
						send("Fail");
					}

					// world.getPlayerId
					break;
				}
				case "world.getPlayerId":
					Player p = plugin.getNamedPlayer(args[0]);
					if (p != null) {
						send(p.getEntityId());
					} else {
						plugin.getLogger().info("Player [" + args[0] + "] not found.");
						send("Fail");
					}

					// entity.getListName
					break;
				case "entity.getName":
					Entity e = plugin.getEntity(Integer.parseInt(args[0]));
					if (e == null) {
						plugin.getLogger().info("Player (or Entity) [" + args[0] + "] not found in entity.getName.");
					} else if (e instanceof Player) {
						Player p_lc = (Player) e;
						//sending list name because plugin.getNamedPlayer() uses list name
						send(p_lc.getPlayerListName());
					} else {
						send(e.getName());
					}

					// world.getEntities
					break;
				case "world.getEntities": {
					int entityType = Integer.parseInt(args[0]);
					send(getEntities(world, entityType));

					// world.removeEntity
					break;
				}
				case "world.removeEntity":
					int result = 0;
					for (Entity e_ct : world.getEntities()) {
						if (e_ct.getEntityId() == Integer.parseInt(args[0])) {
							e_ct.remove();
							result = 1;
							break;
						}
					}
					send(result);

					// world.removeEntities
					break;
				case "world.removeEntities": {
					int entityType = Integer.parseInt(args[0]);
					int removedEntitiesCount = 0;
					for (Entity e_ct : world.getEntities()) {
						if (entityType == -1 || e_ct.getType().getTypeId() == entityType) {
							e_ct.remove();
							removedEntitiesCount++;
						}
					}
					send(removedEntitiesCount);

					// chat.post
					break;
				}
				case "chat.post":
					//create chat message from args as it was split by ,
					StringBuilder chatMessage = new StringBuilder();
					int count;
					for (count = 0; count < args.length; count++) {
						chatMessage.append(args[count]).append(",");
					}
					chatMessage = new StringBuilder(chatMessage.substring(0, chatMessage.length() - 1));
					server.broadcastMessage(chatMessage.toString());

					// events.clear
					break;
				case "events.clear":
					interactEventQueue.clear();
					chatPostedQueue.clear();
					// events.block.hits
					break;
				case "events.chat.posts":
					send(getChatPosts());

					// events.projectile.hits
					break;
				case "entity.events.clear": {
					int entityId = Integer.parseInt(args[0]);
					clearEntityEvents(entityId);

					// entity.events.block.hits
					break;
				}
				case "entity.events.chat.posts": {
					int entityId = Integer.parseInt(args[0]);
					send(getChatPosts(entityId));

					// entity.events.projectile.hits
					break;
				}
				case "player.getTile": {
					Player currentPlayer = getCurrentPlayer();
					if (currentPlayer==null) {
						send("Fail");
						break;
					}
					send(blockLocationToRelative(currentPlayer.getLocation()));

					// player.setTile
					break;
				}
				case "player.setTile": {
					String x = args[0], y = args[1], z = args[2];
					Player currentPlayer = getCurrentPlayer();
					if (currentPlayer==null) {
						break;
					}
					//get players current location, so when they are moved we will use the same pitch and yaw (rotation)
					Location loc = currentPlayer.getLocation();
					currentPlayer.teleport(parseRelativeBlockLocation(x, y, z, loc.getPitch(), loc.getYaw()));

					// player.getAbsPos
					break;
				}
				case "player.getAbsPos": {
					Player currentPlayer = getCurrentPlayer();
					if (currentPlayer==null) {
						send("Fail");
						break;
					}
					send(currentPlayer.getLocation());

					// player.setAbsPos
					break;
				}
				case "player.setAbsPos": {
					String x = args[0], y = args[1], z = args[2];
					Player currentPlayer = getCurrentPlayer();
					if (currentPlayer==null) {
						break;
					}
					//get players current location, so when they are moved we will use the same pitch and yaw (rotation)
					Location loc = currentPlayer.getLocation();
					loc.setX(Double.parseDouble(x));
					loc.setY(Double.parseDouble(y));
					loc.setZ(Double.parseDouble(z));
					currentPlayer.teleport(loc);

					// player.getPos
					break;
				}
				case "player.getPos": {
					Player currentPlayer = getCurrentPlayer();
					if (currentPlayer==null) {
						send("Fail");
						break;
					}
					send(locationToRelative(currentPlayer.getLocation()));

					// player.setPos
					break;
				}
				case "player.setPos": {
					String x = args[0], y = args[1], z = args[2];
					Player currentPlayer = getCurrentPlayer();
					if (currentPlayer==null) {
						break;
					}
					//get players current location, so when they are moved we will use the same pitch and yaw (rotation)
					Location loc = currentPlayer.getLocation();
					currentPlayer.teleport(parseRelativeLocation(x, y, z, loc.getPitch(), loc.getYaw()));

					// player.setDirection
					break;
				}
				case "player.setDirection": {
					double x = Double.parseDouble(args[0]);
					double y = Double.parseDouble(args[1]);
					double z = Double.parseDouble(args[2]);
					Player currentPlayer = getCurrentPlayer();
					if (currentPlayer==null) {
						break;
					}
					Location loc = currentPlayer.getLocation();
					loc.setDirection(new Vector(x, y, z));
					currentPlayer.teleport(loc);

					// player.getDirection
					break;
				}
				case "player.getDirection": {
					Player currentPlayer = getCurrentPlayer();
					if (currentPlayer==null) {
						send("Fail");
						break;
					}
					send(currentPlayer.getLocation().getDirection().toString());

					// player.setRotation
					break;
				}
				case "player.setRotation": {
					float yaw = Float.parseFloat(args[0]);
					Player currentPlayer = getCurrentPlayer();
					if (currentPlayer==null) {
						break;
					}
					Location loc = currentPlayer.getLocation();
					loc.setYaw(yaw);
					currentPlayer.teleport(loc);

					// player.getRotation
					break;
				}
				case "player.getRotation": {
					Player currentPlayer = getCurrentPlayer();
					if (currentPlayer==null) {
						send("Fail");
						break;
					}
					float yaw = currentPlayer.getLocation().getYaw();
					// turn bukkit's 0 - -360 to positive numbers
					if (yaw < 0) yaw = yaw * -1;
					send(yaw);

					// player.setPitch
					break;
				}
				case "player.setPitch": {
					float pitch = Float.parseFloat(args[0]);
					Player currentPlayer = getCurrentPlayer();
					if (currentPlayer==null) {
						break;
					}
					Location loc = currentPlayer.getLocation();
					loc.setPitch(pitch);
					currentPlayer.teleport(loc);

					// player.getPitch
					break;
				}
				case "player.getPitch": {
					Player currentPlayer = getCurrentPlayer();
					if (currentPlayer==null) {
						send("Fail");
						break;
					}
					send(currentPlayer.getLocation().getPitch());

					// player.getEntities
					break;
				}
				case "player.getEntities": {
					Player currentPlayer = getCurrentPlayer();
					if (currentPlayer==null) {
						send("Fail");
						break;
					}
					int distance = Integer.parseInt(args[0]);
					int entityTypeId = Integer.parseInt(args[1]);

					send(getEntities(world, currentPlayer.getEntityId(), distance, entityTypeId));

					// player.removeEntities
					break;
				}
				case "player.removeEntities": {
					Player currentPlayer = getCurrentPlayer();
					if (currentPlayer==null) {
						send("Fail");
						break;
					}
					int distance = Integer.parseInt(args[0]);
					int entityType = Integer.parseInt(args[1]);

					send(removeEntities(world, currentPlayer.getEntityId(), distance, entityType));

					// player.events.block.hits
					break;
				}
				case "player.events.chat.posts": {
					Player currentPlayer = getCurrentPlayer();
					if (currentPlayer==null) {
						send("Fail");
						break;
					}
					send(getChatPosts(currentPlayer.getEntityId()));

					// player.events.projectile.hits
					break;
				}
				case "player.events.clear": {
					Player currentPlayer = getCurrentPlayer();
					if (currentPlayer==null) {
						break;
					}
					clearEntityEvents(currentPlayer.getEntityId());

					// world.getHeight
					break;
				}
				case "world.getHeight":
					send(getHeight(parseRelativeBlockLocation(args[0], "0", args[1])));
					break;
				case "world.getHeights": {
					Location loc1 = parseRelativeBlockLocation(args[0], "0", args[1]);
					Location loc2 = parseRelativeBlockLocation(args[2], "0", args[3]);
					send(getHeights(loc1, loc2));
					break;
				}
				case "entity.getTile": {
					//get entity based on id
					Entity entity = plugin.getEntity(Integer.parseInt(args[0]));
					if (entity != null) {
						send(blockLocationToRelative(entity.getLocation()));
					} else {
						plugin.getLogger().info("Entity [" + args[0] + "] not found.");
						send("Fail");
					}

					// entity.setTile
					break;
				}
				case "entity.setTile": {
					String x = args[1], y = args[2], z = args[3];
					//get entity based on id
					Entity entity = plugin.getEntity(Integer.parseInt(args[0]));
					if (entity != null) {
						//get entity's current location, so when they are moved we will use the same pitch and yaw (rotation)
						Location loc = entity.getLocation();
						entity.teleport(parseRelativeBlockLocation(x, y, z, loc.getPitch(), loc.getYaw()));
					} else {
						plugin.getLogger().info("Entity [" + args[0] + "] not found.");
						send("Fail");
					}

					// entity.getPos
					break;
				}
				case "entity.getPos": {
					//get entity based on id
					Entity entity = plugin.getEntity(Integer.parseInt(args[0]));
					//Player entity = plugin.getEntity(Integer.parseInt(args[0]));
					if (entity != null) {
						send(locationToRelative(entity.getLocation()));
					} else {
						plugin.getLogger().info("Entity [" + args[0] + "] not found.");
						send("Fail");
					}

					// entity.setPos
					break;
				}
				case "entity.setPos": {
					String x = args[1], y = args[2], z = args[3];
					//get entity based on id
					Entity entity = plugin.getEntity(Integer.parseInt(args[0]));
					if (entity != null) {
						//get entity's current location, so when they are moved we will use the same pitch and yaw (rotation)
						Location loc = entity.getLocation();
						entity.teleport(parseRelativeLocation(x, y, z, loc.getPitch(), loc.getYaw()));
					} else {
						plugin.getLogger().info("Entity [" + args[0] + "] not found.");
						send("Fail");
					}

					// entity.setDirection
					break;
				}
				case "entity.setDirection": {
					Entity entity = plugin.getEntity(Integer.parseInt(args[0]));
					if (entity != null) {
						double x = Double.parseDouble(args[1]);
						double y = Double.parseDouble(args[2]);
						double z = Double.parseDouble(args[3]);
						Location loc = entity.getLocation();
						loc.setDirection(new Vector(x, y, z));
						entity.teleport(loc);
					} else {
						plugin.getLogger().info("Entity [" + args[0] + "] not found.");
					}

					// entity.getDirection
					break;
				}
				case "entity.getDirection": {
					//get entity based on id
					Entity entity = plugin.getEntity(Integer.parseInt(args[0]));
					if (entity != null) {
						send(entity.getLocation().getDirection().toString());
					} else {
						plugin.getLogger().info("Entity [" + args[0] + "] not found.");
						send("Fail");
					}

					// entity.setRotation
					break;
				}
				case "entity.setRotation": {
					Entity entity = plugin.getEntity(Integer.parseInt(args[0]));
					if (entity != null) {
						float yaw = Float.parseFloat(args[1]);
						Location loc = entity.getLocation();
						loc.setYaw(yaw);
						entity.teleport(loc);
					} else {
						plugin.getLogger().info("Entity [" + args[0] + "] not found.");
					}

					// entity.getRotation
					break;
				}
				case "entity.getRotation": {
					//get entity based on id
					Entity entity = plugin.getEntity(Integer.parseInt(args[0]));
					if (entity != null) {
						send(entity.getLocation().getYaw());
					} else {
						plugin.getLogger().info("Entity [" + args[0] + "] not found.");
						send("Fail");
					}

					// entity.setPitch
					break;
				}
				case "entity.setPitch": {
					Entity entity = plugin.getEntity(Integer.parseInt(args[0]));
					if (entity != null) {
						float pitch = Float.parseFloat(args[1]);
						Location loc = entity.getLocation();
						loc.setPitch(pitch);
						entity.teleport(loc);
					} else {
						plugin.getLogger().info("Entity [" + args[0] + "] not found.");
					}

					// entity.getPitch
					break;
				}
				case "entity.getPitch": {
					//get entity based on id
					Entity entity = plugin.getEntity(Integer.parseInt(args[0]));
					if (entity != null) {
						send(entity.getLocation().getPitch());
					} else {
						plugin.getLogger().info("Entity [" + args[0] + "] not found.");
						send("Fail");
					}

					// entity.getEntities
					break;
				}
				case "entity.getEntities": {
					int entityId = Integer.parseInt(args[0]);
					int distance = Integer.parseInt(args[1]);
					int entityTypeId = Integer.parseInt(args[2]);

					send(getEntities(world, entityId, distance, entityTypeId));

					// entity.removeEntities
					break;
				}
				case "entity.removeEntities": {
					int entityId = Integer.parseInt(args[0]);
					int distance = Integer.parseInt(args[1]);
					int entityType = Integer.parseInt(args[2]);

					send(removeEntities(world, entityId, distance, entityType));

					// world.setSign
					break;
				}
				case "world.setSign": {
					Location loc = parseRelativeBlockLocation(args[0], args[1], args[2]);
					Block thisBlock = world.getBlockAt(loc);
					//blockType should be 68 for wall sign or 63 for standing sign
					int blockType = Integer.parseInt(args[3]);
					//facing direction for wall sign : 2=north, 3=south, 4=west, 5=east
					//rotation 0 - to 15 for standing sign : 0=south, 4=west, 8=north, 12=east
					byte blockData = Byte.parseByte(args[4]);
					if ((thisBlock.getType().getId() != blockType) || (thisBlock.getData() != blockData)) {
						thisBlock.setTypeIdAndData(blockType, blockData, true);
					}
					//plugin.getLogger().info("Creating sign at " + loc);
					if (thisBlock.getState() instanceof Sign) {
						Sign sign = (Sign) thisBlock.getState();
						for (int i = 5; i - 5 < 4 && i < args.length; i++) {
							sign.setLine(i - 5, args[i]);
						}
						sign.update();
					}

					// world.spawnEntity
					break;
				}
				case "world.spawnEntity": {
					Location loc = parseRelativeBlockLocation(args[0], args[1], args[2]);
					Entity entity = world.spawnEntity(loc, EntityType.fromId(Integer.parseInt(args[3])));
					send(entity.getEntityId());

					// world.getEntityTypes
					break;
				}
				case "world.getEntityTypes": {
					StringBuilder bdr = new StringBuilder();
					for (EntityType entityType : EntityType.values()) {
						if (entityType.isSpawnable() && entityType.getTypeId() >= 0) {
							bdr.append(entityType.getTypeId());
							bdr.append(",");
							bdr.append(entityType);
							bdr.append("|");
						}
					}
					send(bdr.toString());

					// not a command which is supported
					break;
				}
				default:
					plugin.getLogger().warning(c + " is not supported.");
					send("Fail");
					break;
			}
		} catch (Exception e) {
			
			plugin.getLogger().warning("Error occurred handling command");
			e.printStackTrace();
			send("Fail");
		
		}
	}

	private boolean isBlockSolid(Block block) {
		if (!block.getType().isBlock() || block.getType().getId() == 0) {
			return false;
		}
		switch (block.getType()) {
			case AIR:
			case SAPLING:
			case POWERED_RAIL:
			case DETECTOR_RAIL:
			case LONG_GRASS:
			case DEAD_BUSH:
			case PISTON_MOVING_PIECE:
			case YELLOW_FLOWER:
			case RED_ROSE:
			case BROWN_MUSHROOM:
			case RED_MUSHROOM:
			case TORCH:
			case FIRE:
			case REDSTONE_WIRE:
			case CROPS:
			case SIGN_POST:
			case LADDER:
			case RAILS:
			case WALL_SIGN:
			case LEVER:
			case STONE_PLATE:
			case WOOD_PLATE:
			case REDSTONE_TORCH_OFF:
			case REDSTONE_TORCH_ON:
			case STONE_BUTTON:
			case SNOW:
			case SUGAR_CANE_BLOCK:
			case DIODE_BLOCK_OFF:
			case DIODE_BLOCK_ON:
			case TRAP_DOOR:
			case PUMPKIN_STEM:
			case MELON_STEM:
			case VINE:
			case WATER_LILY:
			case NETHER_WARTS:
			case COCOA:
			case TRIPWIRE_HOOK:
			case TRIPWIRE:
			case CARROT:
			case POTATO:
			case WOOD_BUTTON:
			case GOLD_PLATE:
			case IRON_PLATE:
			case REDSTONE_COMPARATOR_OFF:
			case REDSTONE_COMPARATOR_ON:
			case ACTIVATOR_RAIL:
			case DOUBLE_PLANT:
			case STANDING_BANNER:
			case WALL_BANNER:
			case BEETROOT_BLOCK:
			case STRUCTURE_VOID:
				return false;
			default:
				return true;
		}
	}

	private int getHeight(Location loc) {
		final World world = loc.getWorld();
		loc.setY(255);
		while (loc.getY() >= 0) {
			final Block block = world.getBlockAt(loc);
			if (isBlockSolid(block)) {
				return (int)loc.getY() + 1;
			}
			loc.setY(loc.getY() - 1);
		}
		return 0;
	}

	// create a cuboid of lots of blocks 
	private void setCuboid(Location pos1, Location pos2, int blockType, byte data) {
		int minX, maxX, minY, maxY, minZ, maxZ;
		World world = pos1.getWorld();
		minX = Math.min(pos1.getBlockX(), pos2.getBlockX());
		maxX = Math.max(pos1.getBlockX(), pos2.getBlockX());
		minY = Math.min(pos1.getBlockY(), pos2.getBlockY());
		maxY = Math.max(pos1.getBlockY(), pos2.getBlockY());
		minZ = Math.min(pos1.getBlockZ(), pos2.getBlockZ());
		maxZ = Math.max(pos1.getBlockZ(), pos2.getBlockZ());

		for (int x = minX; x <= maxX; ++x) {
			for (int z = minZ; z <= maxZ; ++z) {
				for (int y = minY; y <= maxY; ++y) {
					updateBlock(world, x, y, z, blockType, data);
				}
			}
		}
	}

	// get a cuboid of lots of blocks
	private String getBlocks(Location pos1, Location pos2, boolean withData) {
		StringBuilder blockData = new StringBuilder();

		int minX, maxX, minY, maxY, minZ, maxZ;
		World world = pos1.getWorld();
		minX = Math.min(pos1.getBlockX(), pos2.getBlockX());
		maxX = Math.max(pos1.getBlockX(), pos2.getBlockX());
		minY = Math.min(pos1.getBlockY(), pos2.getBlockY());
		maxY = Math.max(pos1.getBlockY(), pos2.getBlockY());
		minZ = Math.min(pos1.getBlockZ(), pos2.getBlockZ());
		maxZ = Math.max(pos1.getBlockZ(), pos2.getBlockZ());

		for (int y = minY; y <= maxY; ++y) {
			 for (int x = minX; x <= maxX; ++x) {
				 for (int z = minZ; z <= maxZ; ++z) {
					 Block currBlock = world.getBlockAt(new Location(world, x, y, z));
					 blockData.append(currBlock.getType().getId()).append(",");
					 //adds data
					 if (withData) blockData.append(currBlock.getData()).append(";");
				}
			}
		}

		return blockData.substring(0, blockData.length() > 0 ? blockData.length() - 1 : 0);	// We don't want last comma
	}

	private String getHeights(Location pos1, Location pos2) {
		StringBuilder heightData = new StringBuilder();

		World world = pos1.getWorld();
		int minX = Math.min(pos1.getBlockX(), pos2.getBlockX());
		int maxX = Math.max(pos1.getBlockX(), pos2.getBlockX());
		int minZ = Math.min(pos1.getBlockZ(), pos2.getBlockZ());
		int maxZ = Math.max(pos1.getBlockZ(), pos2.getBlockZ());

		for (int x = minX; x <= maxX; ++x) {
			for (int z = minZ; z <= maxZ; ++z) {
				// Find the highest block at this x, z coordinate
				int height = getHeight(new Location(world, x, 0, z));
				// Store the height in string
				heightData.append(height).append(',');
			}
		}

		return heightData.substring(0, heightData.length() > 0 ? heightData.length() - 1 : 0);	// We don't want last comma
	}

	// updates a block
	private void updateBlock(World world, Location loc, int blockType, byte blockData) {
		Block thisBlock = world.getBlockAt(loc);
		updateBlock(thisBlock, blockType, blockData);
	}
	
	private void updateBlock(World world, int x, int y, int z, int blockType, byte blockData) {
		Block thisBlock = world.getBlockAt(x,y,z);
		updateBlock(thisBlock, blockType, blockData);
	}
	
	private void updateBlock(Block thisBlock, int blockType, byte blockData) {
		// check to see if the block is different - otherwise leave it 
		if ((thisBlock.getType().getId() != blockType) || (thisBlock.getData() != blockData)) {
			thisBlock.setTypeIdAndData(blockType, blockData, true);
		}
	}
	
	// gets the current player
	public Player getCurrentPlayer() {
		Player player = attachedPlayer;
		// if the player hasn't already been retrieved for this session, go and get it.
		if (player == null) {
			player = plugin.getHostPlayer();
			attachedPlayer = player;
		}
		if (player==null) {
			plugin.getLogger().warning("[!!WARNING!!] Valid player entity does not exist on server.");
		}
		return player;
	}


	public Location parseRelativeBlockLocation(String xstr, String ystr, String zstr) {
		int x = (int) Double.parseDouble(xstr);
		int y = (int) Double.parseDouble(ystr);
		int z = (int) Double.parseDouble(zstr);
		return parseLocation(origin.getWorld(), x, y, z, origin.getBlockX(), origin.getBlockY(), origin.getBlockZ());
	}

	public Location parseRelativeLocation(String xstr, String ystr, String zstr) {
		double x = Double.parseDouble(xstr);
		double y = Double.parseDouble(ystr);
		double z = Double.parseDouble(zstr);
		return parseLocation(origin.getWorld(), x, y, z, origin.getX(), origin.getY(), origin.getZ());
	}

	public Location parseRelativeBlockLocation(String xstr, String ystr, String zstr, float pitch, float yaw) {
		Location loc = parseRelativeBlockLocation(xstr, ystr, zstr);
		loc.setPitch(pitch);
		loc.setYaw(yaw);
		return loc;
	}

	public Location parseRelativeLocation(String xstr, String ystr, String zstr, float pitch, float yaw) {
		Location loc = parseRelativeLocation(xstr, ystr, zstr);
		loc.setPitch(pitch);
		loc.setYaw(yaw);
		return loc;
	}

	public String blockLocationToRelative(Location loc) {
		return parseLocation(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), origin.getBlockX(), origin.getBlockY(), origin.getBlockZ());
	}

	public String locationToRelative(Location loc) {
		return parseLocation(loc.getX(), loc.getY(), loc.getZ(), origin.getX(), origin.getY(), origin.getZ());
	}

	private String parseLocation(double x, double y, double z, double originX, double originY, double originZ) {
		return (x - originX) + "," + (y - originY) + "," + (z - originZ);
	}

	private Location parseLocation(World world, double x, double y, double z, double originX, double originY, double originZ) {
		return new Location(world, originX + x, originY + y, originZ + z);
	}

	private String parseLocation(int x, int y, int z, int originX, int originY, int originZ) {
		return (x - originX) + "," + (y - originY) + "," + (z - originZ);
	}

	private Location parseLocation(World world, int x, int y, int z, int originX, int originY, int originZ) {
		return new Location(world, originX + x, originY + y, originZ + z);
	}

	private double getDistance(Entity ent1, Entity ent2) {
		if (ent1 == null || ent2 == null)
			return -1;
		double dx = ent2.getLocation().getX() - ent1.getLocation().getX();
		double dy = ent2.getLocation().getY() - ent1.getLocation().getY();
		double dz = ent2.getLocation().getZ() - ent1.getLocation().getZ();
		return Math.sqrt(dx*dx + dy*dy + dz*dz);
	}

	private String getEntities(World world, int entityType) {
		StringBuilder bdr = new StringBuilder();				
		for (Entity e : world.getEntities()) {
			if (((entityType == -1 && e.getType().getTypeId() >= 0) || e.getType().getTypeId() == entityType) && 
				e.getType().isSpawnable()) {
				bdr.append(getEntityMsg(e));
			}
		}
		return bdr.toString();
	}
	
	private String getEntities(World world, int entityId, int distance, int entityType) {
		Entity playerEntity = plugin.getEntity(entityId);
		StringBuilder bdr = new StringBuilder();
		for (Entity e : world.getEntities()) {
			if (((entityType == -1 && e.getType().getTypeId() >= 0) || e.getType().getTypeId() == entityType) && 
				e.getType().isSpawnable() && 
				getDistance(playerEntity, e) <= distance) {
				bdr.append(getEntityMsg(e));
			}
		}
		return bdr.toString();
	}

	private String getEntityMsg(Entity entity) {
		return entity.getEntityId() +
				"," +
				entity.getType().getTypeId() +
				"," +
				entity.getType().toString() +
				"," +
				entity.getLocation().getX() +
				"," +
				entity.getLocation().getY() +
				"," +
				entity.getLocation().getZ() +
				"|";
	}

	private int removeEntities(World world, int entityId, int distance, int entityType) {
		int removedEntitiesCount = 0;
		Entity playerEntityId = plugin.getEntity(entityId);
		for (Entity e : world.getEntities()) {
			if ((entityType == -1 || e.getType().getTypeId() == entityType) && getDistance(playerEntityId, e) <= distance)
			{
				e.remove();
				removedEntitiesCount++;
			}
		}
		return removedEntitiesCount;
	}

	private String getChatPosts() {
		return getChatPosts(-1);
	}

	private String getChatPosts(int entityId) {
		StringBuilder b = new StringBuilder();
		for (Iterator<AsyncPlayerChatEvent> iter = chatPostedQueue.iterator(); iter.hasNext(); ) {
			AsyncPlayerChatEvent event = iter.next();
			if (entityId == -1 || event.getPlayer().getEntityId() == entityId) {
				b.append(event.getPlayer().getEntityId());
				b.append(",");
				b.append(event.getMessage());
				b.append("|");
				iter.remove();
			}
		}
		if (b.length() > 0)
			b.deleteCharAt(b.length() - 1);
		 return b.toString();
	}

	private void clearEntityEvents(int entityId) {
		for (Iterator<PlayerInteractEvent> iter = interactEventQueue.iterator(); iter.hasNext(); ) {
			PlayerInteractEvent event = iter.next();
			if (event.getPlayer().getEntityId() == entityId)
				iter.remove();
		}
		for (Iterator<AsyncPlayerChatEvent> iter = chatPostedQueue.iterator(); iter.hasNext(); ) {
			AsyncPlayerChatEvent event = iter.next();
			if (event.getPlayer().getEntityId() == entityId)
				iter.remove();
		}
		for (Iterator<ProjectileHitEvent> iter = projectileHitQueue.iterator(); iter.hasNext(); ) {
			ProjectileHitEvent event = iter.next();
			Arrow arrow = (Arrow) event.getEntity();
			LivingEntity shooter = (LivingEntity)arrow.getShooter();
			if (shooter.getEntityId() == entityId)
				iter.remove();
		}
	}
				
	public void send(Object a) {
		send(a.toString());
	}

	public void send(String a) {
		if (pendingRemoval) return;
		synchronized(outQueue) {
			outQueue.add(a);
		}
	}

	public void close() {
		if (closed) return;
		running = false;
		pendingRemoval = true;

		//wait for threads to stop
		try {
			inThread.join(2000);
			outThread.join(2000);
		}
		catch (InterruptedException e) {
			plugin.getLogger().warning("Failed to stop in/out thread");
			e.printStackTrace();
		}

		try {
			socket.close();
		} catch (Exception e) {
			e.printStackTrace();
		}
		plugin.getLogger().info("Closed connection to" + socket.getRemoteSocketAddress() + ".");
	}

	/** socket listening thread */
	private class InputThread implements Runnable {
		public void run() {
			plugin.getLogger().info("Starting input thread");
			while (running) {
				try {
					String newLine = in.readLine();
					//System.out.println(newLine);
					if (newLine == null) {
						running = false;
					} else {
						inQueue.add(newLine);
						//System.out.println("Added to in queue");
					}
				} catch (Exception e) {
					// if its running raise an error
					if (running) {
						if (e.getMessage().equals("Connection reset")) {
							plugin.getLogger().info("Connection reset");
						} else {
							e.printStackTrace();
						}
						running = false;
					}
				} 
			}
			//close in buffer
			try {
				in.close();
			} catch (Exception e) {
				plugin.getLogger().warning("Failed to close in buffer");
				e.printStackTrace();
			}
		}
	}

	private class OutputThread implements Runnable {
		public void run() {
			plugin.getLogger().info("Starting output thread!");
			while (running) {
				try {
					String line;
					while((line = outQueue.poll()) != null) {
						out.write(line);
						out.write('\n');
					}
					out.flush();
					Thread.yield();
					Thread.sleep(1L);
				} catch (Exception e) {
					// if its running raise an error
					if (running) {
						e.printStackTrace();
						running = false;
					}
				}
			}
			//close out buffer
			try {
				out.close();
			} catch (Exception e) {
				plugin.getLogger().warning("Failed to close out buffer");
				e.printStackTrace();
			}
		}
	}

}
