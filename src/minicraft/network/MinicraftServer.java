package minicraft.network;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FilenameFilter;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import minicraft.Game;
import minicraft.entity.Bed;
import minicraft.entity.Chest;
import minicraft.entity.Entity;
import minicraft.entity.Furniture;
import minicraft.entity.ItemEntity;
import minicraft.entity.Player;
import minicraft.entity.RemotePlayer;
import minicraft.entity.particle.Particle;
import minicraft.item.Item;
import minicraft.item.Items;
import minicraft.item.PotionItem;
import minicraft.item.PotionType;
import minicraft.level.Level;
import minicraft.level.tile.Tile;
import minicraft.saveload.Load;
import minicraft.saveload.Save;
import minicraft.screen.ModeMenu;
import minicraft.screen.WorldSelectMenu;

public class MinicraftServer extends Thread implements MinicraftProtocol {
	
	private ArrayList<MinicraftServerThread> threadList = new ArrayList<MinicraftServerThread>();
	private ServerSocket socket;
	
	private Game game;
	private RemotePlayer hostPlayer = null;
	private String worldPath;
	
	public MinicraftServer(Game game) {
		super("MinicraftServer");
		this.game = game;
		
		game.ISONLINE = true;
		game.ISHOST = true; // just in case.
		game.player.remove(); // the server has no player...
		
		worldPath = Game.gameDir + "/saves/" + WorldSelectMenu.worldname;
		
		try {
			System.out.println("opening server socket...");
			socket = new ServerSocket(PORT);
			start();
		} catch (IOException ex) {
			System.err.println("failed to open server socket on port " + PORT);
			ex.printStackTrace();
		}
	}
	
	public void run() {
		if(Game.debug) System.out.println("server started.");
		
		try {
			while (socket != null) {
				MinicraftServerThread mst = new MinicraftServerThread(game, socket.accept(), this);
				threadList.add(mst);
			}
		} catch (SocketException ex) { // this should occur when closing the thread.
			//ex.printStackTrace();
		} catch (IOException ex) {
			System.err.println("server socket encountered an error while attempting to listen on port " + PORT + ":");
			ex.printStackTrace();
		}
		
		System.out.println("closing server socket");
		
		endConnection();
	}
	
	public String getWorldPath() { return worldPath; }
	
	public synchronized MinicraftServerThread[] getThreads() {
		return threadList.toArray(new MinicraftServerThread[0]);
	}
	
	public static List<RemotePlayer> getPlayersInRange(Entity e, boolean useTrackRange) {
		if(e == null || e.getLevel() == null) return new ArrayList<RemotePlayer>();
		int xt = e.x >> 4, yt = e.y >> 4;
		return getPlayersInRange(e.getLevel(), xt, yt, useTrackRange); // NOTE if "e" is a RemotePlayer, the list returned *will* contain "e".
	}
	public static List<RemotePlayer> getPlayersInRange(Level level, int xt, int yt, boolean useTrackRange) {
		List<RemotePlayer> players = new ArrayList<RemotePlayer>();
		//if(e == null || e.getLevel() == null) return players;
		/// screen is 18 tiles hori, 14 tiles vert. So, rect is 20x16 tiles.
		//List<Entity> entities = level.getEntitiesInTiles(xt - RemotePlayer.xSyncRadius, yt - RemotePlayer.ySyncRadius, xt + RemotePlayer.xSyncRadius, yt + RemotePlayer.ySyncRadius);
		for(Entity e: level.getEntityArray()) {
			if(e instanceof RemotePlayer) {
				RemotePlayer rp = (RemotePlayer)e;
				if(useTrackRange && rp.shouldTrack(xt, yt) || !useTrackRange && rp.shouldSync(xt, yt))
					players.add(rp);
			}
		}
		
		return players;
	}
	
	private RemotePlayer getIfPlayer(Entity e) {
		if(e instanceof RemotePlayer) {
			RemotePlayer given = (RemotePlayer) e;
			MinicraftServerThread filed = getMatchingThread(given);
			if(filed == null) {
				System.err.println("SERVER encountered a RemotePlayer not matched in the thread list: " + given);
				return null;
			}
			return filed.getClient();
		}
		else
			return null;
	}
	
	public MinicraftServerThread getMatchingThread(RemotePlayer player) {
		MinicraftServerThread thread = null;
		
		for(MinicraftServerThread curThread: getThreads()) {
			if(curThread.getClient() == player) {
				thread = curThread;
				break;
			}
		}
		
		if(thread == null) {
			System.out.println("SERVER could not find thread for remote player " + player + "; stack trace:");
			Thread.dumpStack();
		}
		
		return thread;
	}
	
	private List<MinicraftServerThread> getAssociatedThreads(List<RemotePlayer> players) {
		List<MinicraftServerThread> threads = new ArrayList<MinicraftServerThread>();
		
		/// NOTE I could do this the other way around, by looping though the thread list, and adding those whose player is found in the given list, which might be slightly more optimal... but I think it's better that this tells you when a player in the list doesn't have a matching thread.
		for(RemotePlayer client: players) {
			MinicraftServerThread thread = getMatchingThread(client);
			if(thread != null)
				threads.add(thread);
			else
				System.err.println("SERVER WARNING: couldn't find server thread for client " + client);
		}
		
		return threads;
	}
	
	public void broadcastEntityUpdate(Entity e) { broadcastEntityUpdate(e, false); }
	public void broadcastEntityUpdate(Entity e, boolean updateSelf) {
		List<RemotePlayer> players = getPlayersInRange(e, false);
		//if(Game.debug && e instanceof Player) System.out.println("SERVER found " + players.size() + " players in range of " + e + ", inc self.");
		if(!updateSelf) {
			players.remove(getIfPlayer(e));
			//if(Game.debug && e instanceof Player) System.out.println("Server removed player "+e+" of update: " + removed);
		}
		
		for(MinicraftServerThread thread: getAssociatedThreads(players)) {
			thread.sendEntityUpdate(e, e.getUpdates());
		}
		
		e.flushUpdates(); // it is important that this method is only called once: right here.
	}
	
	public void broadcastTileUpdate(Level level, int x, int y) {
		broadcastData(InputType.TILE, Tile.getData(level.depth, x, y));
	}
	/*public void sendTileUpdate(int x, int y, RemotePlayer client) {
		if(client == null || client.getLevel() == null) {
			System.err.println("SERVER: can't update tile for null player, or player without level: " + client);
			return;
		}
		
		sendData(prependType(InputType.TILE, getTileBytes(client.getLevel().depth, x, y)), client.ipAddress, client.port);
	}*/
	
	public void broadcastEntityAddition(Entity e) { broadcastEntityAddition(e, false); }
	public void broadcastEntityAddition(Entity e, boolean addSelf) {
		List<RemotePlayer> players = getPlayersInRange(e, true);
		if(!addSelf)
			players.remove(getIfPlayer(e)); // if "e" is a player, this removes it from the list.
		for(MinicraftServerThread thread: getAssociatedThreads(players))
			thread.sendEntityAddition(e);
	}
	
	//public void sendEntityAddition(Entity e, RemotePlayer sender) { sendEntityAddition(e, sender, false); }
	
	public void broadcastEntityRemoval(Entity e) { broadcastEntityRemoval(e, false); }
	public void broadcastEntityRemoval(Entity e, boolean removeSelf) {
		List<RemotePlayer> players = getPlayersInRange(e, true);
		players.remove(getIfPlayer(e)); // if "e" is a player, this removes it from the list.
		for(MinicraftServerThread thread: getAssociatedThreads(players))
			thread.sendEntityRemoval(e.eid);
	}
	//public void sendEntityRemoval(Entity e) { sendEntityRemoval(e, getIfPlayer(e)); }
	//public void sendEntityRemoval(Entity e, RemotePlayer sender) { sendEntityRemoval(e.eid, sender, false); }
	
	public void saveWorld() {
		broadcastData(InputType.SAVE, ""); // tell all the other clients to send their data over to be saved.
		new Save(game, WorldSelectMenu.worldname);
	}
	
	public void broadcastNotification(String note, int notetime) {
		String data = notetime + ";" + note;
		broadcastData(InputType.NOTIFY, data);
	}
	
	public void updateGameVars() { updateGameVars(getThreads()); }
	public void updateGameVars(MinicraftServerThread sendTo) {
		updateGameVars(new MinicraftServerThread[] {sendTo});
	}
	public void updateGameVars(MinicraftServerThread[] sendTo) {
		String[] varArray = {
			ModeMenu.mode+"",
			Game.tickCount+"",
			Game.gamespeed+"",
			Game.pastDay1+"",
			Game.scoreTime+""
		};
		
		String vars = "";
		for(String var: varArray)
			vars += var+";";
		
		vars = vars.substring(0, vars.length()-1);
		
		for(MinicraftServerThread thread: sendTo)
			thread.sendData(InputType.GAME, vars);
	}
	
	protected File[] getRemotePlayerFiles() {
		File saveFolder = new File(worldPath);
		
		File[] clientSaves = saveFolder.listFiles(new FilenameFilter() {
			public boolean accept(File file, String name) {
				return name.startsWith("RemotePlayer");
			}
		});
		
		if(clientSaves == null)
			clientSaves = new File[0];
		
		return clientSaves;
	}
	
	public synchronized boolean parsePacket(MinicraftServerThread serverThread, InputType inType, String alldata) {
		String[] data = alldata.split(";");
		
		RemotePlayer clientPlayer = serverThread.getClient();
		if(clientPlayer == null) {
			System.err.println("CRITICAL SERVER ERROR: server thread client is null: " + serverThread + "; cannot parse the recieved "+inType+" packet: " + alldata);
			return false;
		}
		
		// handle reports of type INVALID
		if(inType == InputType.INVALID) {
			if (Game.debug) System.out.println(serverThread + " recieved an error:");
			System.err.println(alldata);
			return false;
		}
		
		if(InputType.serverOnly.contains(inType)) {
			/// these are ALL illegal for a client to send.
			System.err.println("SERVER warning: client " + clientPlayer + " sent illegal packet type " + inType + " to server.");
			//sendError("You cannot set the world variables.", address, port);
			return false;
		}
		
		switch(inType) {
			case PING:
				if (Game.debug) System.out.println(serverThread + " recieved ping");
				serverThread.sendData(InputType.PING, "");
				return true;
			
			case USERNAMES:
				if (Game.debug) System.out.println("SERVER: recieved usernames request");
				String names = "";
				for(MinicraftServerThread thread: getThreads())
					names += thread.getClient().getUsername() + "\n";
				
				serverThread.sendData(InputType.USERNAMES, names);
				return true;
			
			case LOGIN:
				if (Game.debug) System.out.println("SERVER: recieved login request");
				if (Game.debug) System.out.println("SERVER: login data: " + Arrays.toString(data));
				String username = data[0];
				Load.Version clientVersion = new Load.Version(data[1]);
				// check version; send back invalid if they don't match.
				if(clientVersion.compareTo(new Load.Version(Game.VERSION)) != 0) {
					serverThread.sendError("wrong game version; need " + Game.VERSION);
					return false;
				}
				
				// TODO check username
				
				/// versions match; make client player
				clientPlayer.setUsername(username);
				//RemotePlayer clientPlayer = new RemotePlayer(null, game, address, port);
				
				/// now, we need to check if this player has played in this world before. If they have, then all previoeus settings and items and such will be restored.
				String playerdata = ""; // this stores the data fetched from the files.
				
				if(serverThread.getClient().getIpAddress().isLoopbackAddress() && hostPlayer == null) {
					/// this is the first person on localhost. I believe that a second person will be saved as a loopback address (later: jk the first one actually will), but a third will simply overwrite the second.
					if (Game.debug) System.out.println("SERVER: host player found");
					
					if(game.player != null) {
						// save the player, and then remove it.
						playerdata = game.player.getData();
						
						//if (Game.debug) System.out.println("SERVER: setting main player as remote from login.");
						game.player.remove(); // all the important data has been saved.
						game.player = null;
					} else {
						/// load the data from file instead.
						try {
							playerdata = Load.loadFromFile(worldPath+"/Player"+Save.extention, true) + "\n";
							playerdata += Load.loadFromFile(worldPath+"/Inventory"+Save.extention, true);
						} catch(IOException ex) {
							System.err.println("SERVER had error while trying to load host player data from file:");
							ex.printStackTrace();
						}
					}
					
					hostPlayer = clientPlayer;
				}
				else {
					playerdata = serverThread.getRemotePlayerFileData();
				}
				
				if(playerdata.length() > 0) {
					/// if a save file was found, then send the data to the client so they can resume where they left off.
					//if (Game.debug) System.out.println("SERVER: sending saved player data: " + playerdata);
					serverThread.sendData(InputType.PLAYER, playerdata);
					// and now, initialize the RemotePlayer instance with the data.
					(new Load()).loadPlayer(clientPlayer, Arrays.asList(playerdata.split("\\n")[0].split(",")));
					// we really don't need to load the inventory.
				} else
					clientPlayer.findStartPos(Game.levels[Game.lvlIdx(0)]); // find a new start pos
				
				// now, we send the INIT_W packet and notify the others clients.
				
				int playerlvl = Game.lvlIdx(clientPlayer.getLevel() != null ? clientPlayer.getLevel().depth : 0);
				if(!Arrays.asList(Game.levels[playerlvl].getEntityArray()).contains(clientPlayer) && clientPlayer != hostPlayer) // this will be true if their file was already found, since they are added in Load.loadPlayer().
					Game.levels[playerlvl].add(clientPlayer); // add to level (**id is generated here**) and also, maybe, broadcasted to other players?
				
				updateGameVars(serverThread);
				//making INIT_W packet
				int[] toSend = {
					clientPlayer.eid,
					Game.levels[playerlvl].w,
					Game.levels[playerlvl].h,
					playerlvl,
					clientPlayer.x,
					clientPlayer.y
				};
				String sendString = "";
				for(int val: toSend)
					sendString += val+",";
				/// send client world info
				if (Game.debug) System.out.println("SERVER: sending INIT packet");
				serverThread.sendData(InputType.INIT, sendString);
				//clientList.add(clientPlayer); /// file client player
				//broadcastEntityAddition(clientPlayer); // this is done above.
				//unconfirmedAdditions.put(clientPlayer, new ArrayList<Integer>());
				//unconfirmedRemovals.put(clientPlayer, new ArrayList<Integer>());
				/// tell others of client joining
				//if (Game.debug) System.out.println("SERVER: broadcasting player addition");
				//sendEntityAddition(clientPlayer, clientPlayer);
				return true;
			
			case LOAD:
				if (Game.debug) System.out.println("SERVER: recieved level data request");
				// send back the tiles in the level specified.
				int levelidx = Integer.parseInt(alldata);
				if(levelidx < 0 || levelidx >= Game.levels.length) {
					System.err.println("SERVER warning: Client " + clientPlayer + " tried to request tiles from nonexistent level " + levelidx);
					serverThread.sendError("requested level ("+levelidx+") does not exist.");
					return false;
				}
				
				byte[] tiledata = new byte[Game.levels[levelidx].tiles.length*2];
				//tiledata[0] = (byte) InputType.TILES.ordinal();
				//tiledata[1] = 0; // the index to start on.
				for(int i = 0; i < tiledata.length/2 - 1; i++) {
					tiledata[i*2] = Game.levels[levelidx].tiles[i];
					tiledata[i*2+1] = Game.levels[levelidx].data[i];
				}
				serverThread.cachePacketTypes(InputType.tileUpdates);
				/*int pkSize = packetSize - 2;
				for(int i = 0; i < tiledata.length; i += pkSize) {
					int endidx = i+1+pkSize;
					byte[] curData = Arrays.copyOfRange(tiledata, i, (endidx>=tiledata.length?tiledata.length-1:endidx));
					curData[0] = (byte) InputType.TILES.ordinal();
					curData[1] = (byte) (i/pkSize); // this value tells the client what index to start filling on.
					if(Game.debug) System.out.println("SERVER: sending tiles from " + (i/2) + " to " + ((i+curData.length-2)/2) + " to client; passed value: " + curData[1]);
					sendData(curData, address, port);
				}*/
				String tiledataString = "";
				for(byte b: tiledata)
					tiledataString += (char) ((int)b);
				serverThread.sendData(InputType.TILES, tiledataString);
				serverThread.sendCachedPackets();
				
				/// send back the entities in the level specified.
				
				Entity[] entities = Game.levels[levelidx].getEntityArray();
				serverThread.cachePacketTypes(InputType.entityUpdates);
				//int i = 0;
				//while(i <= entities.length) {
					String edata = "";
					for(int i = 0; i < entities.length; i++) {
						Entity curEntity = entities[i];
						if(!clientPlayer.shouldTrack(curEntity.x>>4, curEntity.y>>4))
							continue; // this is outside of the player's entity tracking range; doesn't need to know about it yet.
						String curEntityData = "";
						if(curEntity != clientPlayer) {
							curEntityData = Save.writeEntity(curEntity, false) + ",";
							if(Game.debug && curEntity instanceof Player) System.out.println("SERVER: sending player in ENTITIES packet: " + curEntity);
						}
						/*if(curEntityData.getBytes().length + edata.getBytes().length + "END".getBytes().length > packetSize-1 && curEntityData.length() > 1) {
							// there are too many entities to send in one packet.
							break;
						}*/
						// there is enough space.
						if(curEntityData.length() > 1) // 1 b/c of the "," added; this prevents entities that aren't saved from causing ",," to appear.
							edata += curEntityData;
					}
					//if(i >= entities.length)
						//edata += "END"; // tell the client there are no more entities to send.
					//else
						edata = edata.substring(0, edata.length()-1); // cut off trailing comma
					
					serverThread.sendData(InputType.ENTITIES, edata);
					serverThread.sendCachedPackets();
					//if(i == entities.length)
						//break; // this won't break in this case otherwise.
				//}
				return true;
			
			case DIE:
				if (Game.debug) System.out.println("recieved player death");
				Entity dc = Load.loadEntity(alldata, game, false);
				broadcastEntityRemoval(clientPlayer);
				return true;
			
			case RESPAWN:
				serverThread.respawnPlayer();
				broadcastEntityAddition(clientPlayer);
				return true;
			
			case DISCONNECT:
				serverThread.endConnection();
				return true;
			
			/*case ENTITY:
				// client wants the specified entity sent in an ADD packet, becuase it couldn't find that entity upon recieving an ENTITY packet from the server.
				int enid = Integer.parseInt(data);
				Entity entityToSend = Game.getEntity(enid);
				if(entityToSend == null) {
					/// well THIS would be a problem, I think. Though... Actually, not really. It just means that an entity was removed between the time of sending an update for it, and the client then asking for it to be added. But since it would be useless to add it at this point, we'll just ignore the request.
					if (Game.debug) System.out.println("SERVER: ignoring request to add unknown entity (probably already removed): " + enid);
					return false;
				}
				
				if(!client.shouldSync(entityToSend.x >> 4, entityToSend.y >> 4)) {
					// the requested entity is not even in range
					return false;
				}
				
				sendEntityAddition(entityToSend, client);
				return true;
			*/
			
			case SAVE:
				if (Game.debug) System.out.println("SERVER: recieved player save");
				/// save this client's data to a file.
				/// first, determine if this is the main player. if not, determine if a file already exists for this client. if not, find an available file name. for simplicity, we will just count the number of remote player files saved.
				
				if(clientPlayer == hostPlayer) {
					if (Game.debug) System.out.println("SERVER: identified SAVE packet client as host");
					String[] parts = alldata.split("\\n");
					List<String> datastrs = new ArrayList<String>();
					
					Save save = new Save(clientPlayer);
					for(String str: parts[0].split(","))
						datastrs.add(str);
					save.writeToFile(save.location+"Player"+Save.extention, datastrs);
					datastrs.clear();
					for(String str: parts[1].split(","))
						datastrs.add(str);
					save.writeToFile(save.location+"Inventory"+Save.extention, datastrs);
					
					return true;
				}
				
				serverThread.writeClientSave(alldata); // writes the data in a RemotePlayer save file.
				
				return true;
			
			case CHESTIN: case CHESTOUT:
				if (Game.debug) System.out.println("SERVER: recieved chest request: " + inType);
				int eid = Integer.parseInt(data[0]);
				Entity e = Game.getEntity(eid);
				if(e == null || !(e instanceof Chest)) {
					System.err.println("SERVER error with CHESTOUT request: Specified chest entity did not exist or was not a chest.");
					return false;
				}
				Chest chest = (Chest) e;
				
				if(inType == InputType.CHESTIN) {
					Item item = Items.get(data[1]);
					if(item == null) {
						System.err.println("SERVER error with CHESTIN request: specified item could not be found from string: " + data[1]);
						return false;
					}
					chest.inventory.add(item);
				}
				else if(inType == InputType.CHESTOUT) {
					int index = Integer.parseInt(data[1]);
					if(index >= chest.inventory.invSize() || index < 0) {
						System.err.println("SERVER error with CHESTOUT request: specified chest inv index is out of bounds: "+index+"; inv size:"+chest.inventory.invSize());
						return false;
					}
					// if here, the index is valid
					Item removed = chest.inventory.remove(index);
					
					if(Game.debug) System.out.println("SERVER sending chestout with item data: \"" + removed.getData() + "\"");
					serverThread.sendData(InputType.CHESTOUT, removed.getData()); // send back the *exact* same packet as was sent; the client will handle it accordingly, by changing their inventory.
				}
				
				serverThread.sendEntityUpdate(chest, chest.getUpdates());
				return true;
			
			case PUSH:
				int furnitureID = Integer.parseInt(alldata);
				Entity furniture = Game.getEntity(furnitureID);
				if(furniture == null) {
					System.err.println("SERVER: couldn't find the specified piece of furniture to push: " + furnitureID);
					return false;
				} else if(!(furniture instanceof Furniture)) {
					System.err.println("SERVER: specified entity is not an instance of the furniture class: " + furniture + "; cannot push.");
					return false;
				}
				
				((Furniture)furniture).tryPush(clientPlayer);
				return true;
			
			case PICKUP:
				//if (Game.debug) System.out.println("SERVER: recieved itementity pickup request");
				int ieid = Integer.parseInt(alldata);
				Entity entity = Game.getEntity(ieid);
				if(entity == null || !(entity instanceof ItemEntity) || entity.isRemoved()) {
					System.err.println("SERVER could not find item entity in PICKUP request: " + ieid + ". Telling client to remove...");
					serverThread.sendEntityRemoval(ieid); // will happen when another guy gets to it first, so the this client shouldn't have it on the level anymore. It could also happen if the client didn't recieve the packet telling them to pick it up... in which case it will be lost, but oh well.
					return false;
				}
				
				entity.remove();
				if(Game.debug) System.out.println("SERVER: item entity pickup approved: " + entity);
				serverThread.sendData(inType, alldata);
				broadcastData(InputType.REMOVE, String.valueOf(entity.eid), serverThread);
				return true;
			
			case INTERACT:
				if (Game.debug) System.out.println("SERVER: recieved interaction request");
				//x, y, dir, item
				// since this should be the most up-to-date data, just update the remote player coords with them.
				int ox = clientPlayer.x>>4, oy = clientPlayer.y>>4;
				//clientPlayer.x = Integer.parseInt(data[0]);
				//clientPlayer.y = Integer.parseInt(data[1]);
				//clientPlayer.dir = Integer.parseInt(data[0]);
				clientPlayer.activeItem = Items.get(data[0]); // this can be null; and that's fine, it means a fist. ;)
				int arrowCount = Integer.parseInt(data[1]);
				int curArrows = clientPlayer.inventory.count(Items.get("arrow"));
				if(curArrows < arrowCount)
					clientPlayer.inventory.add(Items.get("arrow"), arrowCount-curArrows);
				if(curArrows > arrowCount)
					clientPlayer.inventory.removeItems(Items.get("arrow"), curArrows-arrowCount);
				//boolean wasGlove = clientPlayer.activeItem instanceof PowerGloveItem;
				clientPlayer.attack(); /// NOTE the player may fire an arrow, but we won't sync the arrow count because that player will update it theirself.
				
				//boolean pickedUpFurniture = wasGlove && !(clientPlayer.activeItem instanceof PowerGloveItem);
				
				if(!ModeMenu.creative) { // the second part allows the player to pick up furniture in creative mode.
					// now, send back the state of the activeItem. In creative though, this won't change, so it's unnecessary.
					//if(pickedUpFurniture)
						//sendData(InputType.CHESTOUT, Items.get("Power Glove").getData());
					
					if(Game.debug) System.out.println("SERVER: new activeItem for player " + clientPlayer + " after interaction: " + clientPlayer.activeItem);
					serverThread.sendData(InputType.INTERACT, ( clientPlayer.activeItem == null ? "null" : clientPlayer.activeItem.getData() ));
				}
				return true;
			
			case BED:
				Entity bed = Game.getEntity(Integer.parseInt(alldata));
				if(!(bed instanceof Bed)) {
					System.out.println("SERVER: entity is not a bed: " + bed);
					return false;
				}
				bed.use(clientPlayer, 0);
				return true;
			
			case POTION:
				boolean addEffect = Boolean.parseBoolean(data[0]);
				int typeIdx = Integer.parseInt(data[1]);
				PotionItem.applyPotion(clientPlayer, PotionType.values[typeIdx], addEffect);
				return true;
			
			case MOVE:
				/// the player moved.
				//if (Game.debug) System.out.println("SERVER: recieved move packet");
				
				clientPlayer.dir = Integer.parseInt(data[2]);
				int plvlidx = Integer.parseInt(data[3]);
				if(plvlidx >= 0 && plvlidx < Game.levels.length && Game.levels[plvlidx] != clientPlayer.getLevel()) {
					clientPlayer.remove();
					Game.levels[plvlidx].add(clientPlayer);
				}
				
				int oldx = clientPlayer.x>>4, oldy = clientPlayer.y>>4;
				int newx = Integer.parseInt(data[0]);
				int newy = Integer.parseInt(data[1]);
				
				boolean moved = clientPlayer.move(newx - clientPlayer.x, newy - clientPlayer.y); // this moves the player, and updates other clients.
				if(moved) clientPlayer.updateSyncArea(oldx, oldy); // this updates the current client.
				
				broadcastEntityUpdate(clientPlayer, !moved); // this will make it so that if the player is prevented from moving, the server will update the client, forcing it back to the last place the server recorded the player at. TODO this breaks down with a slow connection...
				clientPlayer.walkDist++; // hopefully will make walking animations work. Actually, they should be sent with Mob's update... no, it doesn't update, it just feeds back.
				
				//if (Game.debug) System.out.println("SERVER: "+(moved?"moved player to":"stopped player at")+" ("+clientPlayer.x+","+clientPlayer.y+"): " + clientPlayer);
				
				return true;
			
			/// I'm thinking this should end up never being used... oh, well maybe for notifications, actually.
			default:
				System.out.println("SERVER used default behavior for input type " + inType);
				broadcastData(inType, alldata, serverThread);
				return true;
		}
	}
	
	private void broadcastData(InputType inType, String data) {
		broadcastData(inType, data, (MinicraftServerThread)null);
	}
	private void broadcastData(InputType inType, String data, MinicraftServerThread clientThreadToExclude) {
		for(MinicraftServerThread thread: getThreads()) {
			if(thread != clientThreadToExclude) // send this packet to all EXCEPT the specified one.
				thread.sendData(inType, data);
		}
	}
	private void broadcastData(InputType inType, String data, List<MinicraftServerThread> threads) {
		for(MinicraftServerThread thread: threads)
			thread.sendData(inType, data);
	}
	
	//static int sends = 0;
	/*public void sendData(byte[] data, InetAddress ip, int port) {
		//sends++;
		//if(sends > 50) System.exit(0);
		DatagramPacket packet = new DatagramPacket(data, data.length, ip, port);
		String intype = MinicraftConnection.getInputType(data[0]).name();
		if (Game.debug && !intype.equals("ENTITY") && !intype.equals("ADD") && !intype.equals("REMOVE")) System.out.println("SERVER: sending "+intype+" data to: " + ip);
		try {
			socket.send(packet);
		} catch(IOException ex) {
			ex.printStackTrace();
		}
	}*/
	
	/*public RemotePlayer getClientPlayer(InetAddress ip, int port) {
		for(RemotePlayer client: getClients())
			if(client.ipAddress.equals(ip) && client.port == port)
				return client;
		
		return null;
	}*/
	
	protected void onThreadDisconnect(MinicraftServerThread thread) {
		threadList.remove(thread);
		broadcastData(InputType.REMOVE, String.valueOf(thread.getClient().eid));
		if(thread.getClient() == hostPlayer)
			hostPlayer = null;
	}
	
	public void endConnection() {
		if (Game.debug) System.out.println("SERVER: ending connection");
		
		for(int i = 0; i < threadList.size(); i++) {
			threadList.get(i).endConnection();
		}
		
		try {
			socket.close();
		} catch (IOException ex) {}
		
		threadList.clear();
	}
	
	public boolean isConnected() {
		return socket != null && !socket.isClosed();
	}
	
	public boolean hasClients() {
		return threadList.size() > 0;
	}
}
