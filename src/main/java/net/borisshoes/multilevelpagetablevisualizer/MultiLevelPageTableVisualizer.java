package net.borisshoes.multilevelpagetablevisualizer;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.LiteralTextContent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Pair;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.string;
import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class MultiLevelPageTableVisualizer implements ModInitializer {
   
   private static final Logger logger = LogManager.getLogger("MLPTV"); // Error logger
   public static final ArrayList<Block> BYTE_BLOCKS = new ArrayList<>(); // List of blocks and what byte value they represent
   public static ThreeLevelTable memory; // Memory object of our table simulator
   public static final HashMap<UUID,GenericTimer> SERVER_TIMER_CALLBACKS = new HashMap<>(); // Timer Callbacks
   public static final HashMap<UUID,GenericTimer> SERVER_TIMER_CALLBACKS_QUEUE = new HashMap<>(); // Timer Callbacks waiting to queue
   
   @Override
   public void onInitialize(){
      logger.info("Loading MultiLevelPageTableVisualizer!");
   
      ServerTickEvents.START_SERVER_TICK.register(MultiLevelPageTableVisualizer::onTick);
      CommandRegistrationCallback.EVENT.register(MultiLevelPageTableVisualizer::registerCommands);
      
      loadByteBlocks();
   }
   
   // Registers all the in game commands with appropriate arguments and callbacks
   private static void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess cmdAccess, CommandManager.RegistrationEnvironment regEnv){
      dispatcher.register(literal("mem")
            .then(literal("init").requires(source -> source.hasPermissionLevel(2)).executes(MultiLevelPageTableVisualizer::initMemory))
            .then(literal("delete").requires(source -> source.hasPermissionLevel(2)).executes(MultiLevelPageTableVisualizer::deleteMemory))
            .then(literal("print").requires(source -> source.hasPermissionLevel(2)).executes(MultiLevelPageTableVisualizer::printMemory))
            .then(literal("load").requires(source -> source.hasPermissionLevel(2))
                  .then(literal("page")
                        .then(argument("address",string())
                              .executes(ctx -> MultiLevelPageTableVisualizer.loadPage(ctx.getSource(), getString(ctx,"address")))))
                  .then(literal("table")
                        .then(argument("address",string())
                              .executes(ctx -> MultiLevelPageTableVisualizer.loadTable(ctx.getSource(), getString(ctx,"address"))))))
            .then(literal("pagewalk").requires(source -> source.hasPermissionLevel(2))
                  .then(argument("address",string())
                        .executes(ctx -> MultiLevelPageTableVisualizer.pageWalk(ctx.getSource(), getString(ctx,"address"))))
                  .then(literal("start")
                        .then(argument("address",string())
                              .executes(ctx -> MultiLevelPageTableVisualizer.pageWalkStart(ctx.getSource(), getString(ctx,"address")))))
                  .then(literal("step")
                        .executes((ctx -> MultiLevelPageTableVisualizer.pageWalkStep(ctx.getSource())))))
            .then(literal("test").requires(source -> source.hasPermissionLevel(2)).executes(MultiLevelPageTableVisualizer::testMemory))
      );
   }
   
   
   // Things to do every 1/20 of a second
   private static void onTick(MinecraftServer server){
      try{
         if(server.getTicks() % 20 == 0){ // Runs every second
            for(ServerPlayerEntity player : server.getPlayerManager().getPlayerList()){
               // Get what the player is looking at
               Vec3d end = player.getEyePos().add(player.getRotationVector().normalize().multiply(48));
               ServerWorld world = player.getWorld();
               BlockHitResult result = world.raycast(new RaycastContext(player.getEyePos(),end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE,player));
               // If the player is looking at one of our chosen representation blocks, give a hud update of what value it represents
               if(result.getType() == HitResult.Type.BLOCK){
                  Block hitBlock = world.getBlockState(result.getBlockPos()).getBlock();
                  int ind = BYTE_BLOCKS.indexOf(hitBlock);
                  if(ind != -1){
                     // Check for adjacent blocks to merge with
                     Block left = world.getBlockState(result.getBlockPos().east()).getBlock();
                     Block right = world.getBlockState(result.getBlockPos().west()).getBlock();
                     int leftInd = BYTE_BLOCKS.indexOf(left);
                     int rightInd = BYTE_BLOCKS.indexOf(right);
                     
                     if(leftInd != -1){
                        int value = 256*leftInd + ind;
                        player.sendMessage(Text.literal("") // Show the player what the block represents in base 16, 10 and 2
                              .append(Text.literal(value+" - 0x"+Integer.toHexString(value).toUpperCase()+" - 0b"+Integer.toBinaryString(value)+" - ").formatted(Formatting.AQUA))
                              .append(Text.translatable(left.getName().getString()).formatted(Formatting.DARK_AQUA))
                              .append(Text.literal(" | ").formatted(Formatting.DARK_AQUA))
                              .append(Text.translatable(hitBlock.getName().getString()).formatted(Formatting.DARK_AQUA)),true);
                     }else if(rightInd != -1){
                        int value = 256*ind + rightInd;
                        player.sendMessage(Text.literal("") // Show the player what the block represents in base 16, 10 and 2
                              .append(Text.literal(value+" - 0x"+Integer.toHexString(value).toUpperCase()+" - 0b"+Integer.toBinaryString(value)+" - ").formatted(Formatting.AQUA))
                              .append(Text.translatable(hitBlock.getName().getString()).formatted(Formatting.DARK_AQUA))
                              .append(Text.literal(" | ").formatted(Formatting.DARK_AQUA))
                              .append(Text.translatable(right.getName().getString()).formatted(Formatting.DARK_AQUA)),true);
                     }else{
                        player.sendMessage(Text.literal("") // Show the player what the block represents in base 16, 10 and 2
                              .append(Text.literal(ind+" - 0x"+Integer.toHexString(ind).toUpperCase()+" - 0b"+Integer.toBinaryString(ind)+" - ").formatted(Formatting.AQUA))
                              .append(Text.translatable(hitBlock.getName().getString()).formatted(Formatting.DARK_AQUA)),true);
                     }
                  }else if(hitBlock == Blocks.WHITE_CONCRETE_POWDER || hitBlock == Blocks.GRAY_CONCRETE_POWDER){
                     ArrayList<Integer> bits = new ArrayList<>();
                     for(int i = -3; i <= 3; i++){
                        Block block = world.getBlockState(result.getBlockPos().mutableCopy().add(i,0,0)).getBlock();
                        if(block == Blocks.WHITE_CONCRETE_POWDER){
                           bits.add(1);
                        }else if(block == Blocks.GRAY_CONCRETE_POWDER){
                           bits.add(0);
                        }else{
                           if(i < 0){
                              bits.clear();
                           }else if(i > 0){
                              break;
                           }
                        }
                     }
                     int value = 0;
                     for(int i = 0; i < bits.size(); i++){
                        value += Math.pow(2,i) * bits.get(i);
                     }
                     
                     player.sendMessage(Text.literal("") // Show the player what the block represents in base 16, 10 and 2
                           .append(Text.literal(value+" - 0x"+Integer.toHexString(value).toUpperCase()+" - 0b"+Integer.toBinaryString(value)).formatted(Formatting.AQUA)),true);
                  }
               }
            }
         }
         
         // Tick Timer Callbacks
         ArrayList<UUID> toRemove = new ArrayList<>();
         Iterator<Map.Entry<UUID, GenericTimer>> itr = SERVER_TIMER_CALLBACKS.entrySet().iterator();
         
         while(itr.hasNext()){
            Map.Entry<UUID, GenericTimer> entry = itr.next();
            GenericTimer t = entry.getValue();
            if(t.decreaseTimer() == 0){
               t.onTimer();
               if(t.autoRemove || t.isTrash()) toRemove.add(entry.getKey());
            }
         }
         if(SERVER_TIMER_CALLBACKS_QUEUE.size() > 0){
            SERVER_TIMER_CALLBACKS.putAll(SERVER_TIMER_CALLBACKS_QUEUE);
            SERVER_TIMER_CALLBACKS_QUEUE.clear();
         }
         
         for(UUID uuid : toRemove){
            SERVER_TIMER_CALLBACKS.remove(uuid);
         }
      }catch(Exception e){
         e.printStackTrace();
      }
   }
   
   // Generates in game display and initializes new memory
   private static int initMemory(CommandContext<ServerCommandSource> ctx){
      ServerCommandSource src = ctx.getSource();
      if(!src.isExecutedByPlayer()){
         src.sendFeedback(Text.literal("Command must be executed by player!").formatted(Formatting.RED),false);
         return -1;
      }
      if(memory != null){
         src.sendFeedback(Text.literal("Memory is already initialized!").formatted(Formatting.RED),false);
         return -1;
      }
      memory = new ThreeLevelTable(src.getWorld(), src.getPlayer().getBlockPos());
      memory.constructBox();
      
      ctx.getSource().sendFeedback(Text.literal("Memory initialized!").formatted(Formatting.GREEN),false);
      return 0;
   }
   
   // Deletes the in game display and the contents of memory
   private static int deleteMemory(CommandContext<ServerCommandSource> ctx){
      if(memory == null){
         ctx.getSource().sendFeedback(Text.literal("Memory is not initialized!").formatted(Formatting.RED),false);
         return -1;
      }
      memory.deleteBox();
      memory = null;
      ctx.getSource().sendFeedback(Text.literal("Memory deleted!").formatted(Formatting.RED),false);
      return 0;
   }
   
   // Prints memory and tables to console
   private static int printMemory(CommandContext<ServerCommandSource> ctx){
      if(memory == null){
         ctx.getSource().sendFeedback(Text.literal("Memory is not initialized!").formatted(Formatting.RED),false);
         return -1;
      }
      memory.printTables();
      memory.printMemory();
      ctx.getSource().sendFeedback(Text.literal("Memory dumped to console!").formatted(Formatting.GREEN),false);
      return 0;
   }
   
   // Loads a specific memory page into the in game display, does not affect address shown
   private static int loadPage(ServerCommandSource source, String address){
      if(memory == null){
         source.sendFeedback(Text.literal("Memory is not initialized!").formatted(Formatting.RED),false);
         return -1;
      }
      short vaddr = (short) parseAddress(address);
      if(vaddr < 0 || vaddr >= 4096){
         source.sendFeedback(Text.literal("Invalid Address!").formatted(Formatting.RED),false);
         return -1;
      }
      memory.displayMemPage(memory.mem[vaddr],-1);
      return 0;
   }
   
   // Loads a specific table into the in game display, does not affect address shown
   private static int loadTable(ServerCommandSource source, String address){
      if(memory == null){
         source.sendFeedback(Text.literal("Memory is not initialized!").formatted(Formatting.RED),false);
         return -1;
      }
      short vaddr = (short) parseAddress(address);
      if(vaddr < 0 || vaddr >= 8192){
         source.sendFeedback(Text.literal("Invalid Address!").formatted(Formatting.RED),false);
         return -1;
      }
      memory.displayTablePage((short) (16*(vaddr/16)),vaddr % 16);
      return 0;
   }
   
   // Does a full page walk instantaneously and display physical page
   private static int pageWalk(ServerCommandSource source, String address){
      if(memory == null){
         source.sendFeedback(Text.literal("Memory is not initialized!").formatted(Formatting.RED),false);
         return -1;
      }
      short vaddr = (short) parseAddress(address);
      
      Pair<Integer,Integer> page = memory.pageWalkPage(vaddr);
      if(page.getLeft() == -1 || page.getRight() == -1){
         source.sendFeedback(Text.literal("Page Fault!").formatted(Formatting.RED),false);
         return -1;
      }
      
      memory.displayAddr(vaddr,-1);
      memory.displayMemPage(memory.mem[page.getLeft()],page.getRight());
      
      return 0;
   }
   
   // Starts a new page walk at the given address
   private static int pageWalkStart(ServerCommandSource source, String address){
      if(memory == null){
         source.sendFeedback(Text.literal("Memory is not initialized!").formatted(Formatting.RED),false);
         return -1;
      }
      short vaddr = (short) parseAddress(address);
      
      Pair<Integer,Integer> page = memory.pageWalkPage(vaddr);
      if(page.getLeft() == -1 || page.getRight() == -1){
         source.sendFeedback(Text.literal("Page Fault!").formatted(Formatting.RED),false);
         return -1;
      }
      
      memory.displayAddr(vaddr,0);
      memory.pagewalking = true;
      source.sendFeedback(Text.literal("Starting Page Walk of vaddr: "+Integer.toHexString(vaddr & 0xFFFF)).formatted(Formatting.GREEN),false);
      memory.pageWalkStep(source.getPlayer());
      
      return 0;
   }
   
   // Advances the page walk one step
   private static int pageWalkStep(ServerCommandSource source){
      if(memory == null){
         source.sendFeedback(Text.literal("Memory is not initialized!").formatted(Formatting.RED),false);
         return -1;
      }
      if(!memory.pagewalking){
         source.sendFeedback(Text.literal("No Page Walk active!").formatted(Formatting.RED),false);
         return -1;
      }
      
      memory.displayAddr(memory.curAddr,memory.addrSel+1);
      memory.pageWalkStep(source.getPlayer());
      
      if(memory.addrSel == 3){ // End the page walk
         memory.pagewalking = false;
         source.sendFeedback(Text.literal("Page Walk Complete").formatted(Formatting.GREEN),false);
      }
      return 0;
   }
   
   // Test function for debugging
   private static int testMemory(CommandContext<ServerCommandSource> ctx){
      sendTitleMessage(ctx.getSource().getPlayer(),5,40,20);
      return 0;
   }
   
   // Parses input addresses, allowing for input in base 16, 10 or 2
   private static int parseAddress(String strAddr){
      try{
         String prefix = strAddr.substring(0,2);
         String body = strAddr.substring(2);
         int addr = -1;
         if(prefix.equalsIgnoreCase("0b")){
            addr = Integer.parseInt(body,2);
         }else if(prefix.equalsIgnoreCase("0x")){
            addr = Integer.parseInt(body,16);
         }else{
            addr = Integer.parseInt(strAddr,10);
         }
         if(addr > 0xFFFF || addr < 0){
            addr = -1;
         }
         return addr;
      }catch(Exception e){
         return -1;
      }
   }
   
   public static void sendTitleMessage(ServerPlayerEntity player, int fadeIn, int stay, int fadeOut){
      player.networkHandler.sendPacket(new TitleFadeS2CPacket(fadeIn, stay, fadeOut));
      player.networkHandler.sendPacket(new SubtitleS2CPacket(MutableText.of(new LiteralTextContent("Test Sub Title..."))
            .formatted(Formatting.GREEN, Formatting.ITALIC)));
      player.networkHandler.sendPacket(new TitleS2CPacket(MutableText.of(new LiteralTextContent("Test Title!"))
            .formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD)));
   }
   
   
   // Assigns each possible byte value a block for in world representation
   private static void loadByteBlocks(){
      BYTE_BLOCKS.add(Blocks.STONE);
      BYTE_BLOCKS.add(Blocks.GRANITE);
      BYTE_BLOCKS.add(Blocks.POLISHED_GRANITE);
      BYTE_BLOCKS.add(Blocks.DIORITE);
      BYTE_BLOCKS.add(Blocks.POLISHED_DIORITE);
      BYTE_BLOCKS.add(Blocks.ANDESITE);
      BYTE_BLOCKS.add(Blocks.POLISHED_ANDESITE);
      BYTE_BLOCKS.add(Blocks.DEEPSLATE);
      BYTE_BLOCKS.add(Blocks.COBBLED_DEEPSLATE);
      BYTE_BLOCKS.add(Blocks.POLISHED_DEEPSLATE);
      BYTE_BLOCKS.add(Blocks.CALCITE);
      BYTE_BLOCKS.add(Blocks.TUFF);
      BYTE_BLOCKS.add(Blocks.DRIPSTONE_BLOCK);
      BYTE_BLOCKS.add(Blocks.DIRT);
      BYTE_BLOCKS.add(Blocks.MUD);
      BYTE_BLOCKS.add(Blocks.COBBLESTONE);
      BYTE_BLOCKS.add(Blocks.OAK_PLANKS);
      BYTE_BLOCKS.add(Blocks.SPRUCE_PLANKS);
      BYTE_BLOCKS.add(Blocks.BIRCH_PLANKS);
      BYTE_BLOCKS.add(Blocks.JUNGLE_PLANKS);
      BYTE_BLOCKS.add(Blocks.ACACIA_PLANKS);
      BYTE_BLOCKS.add(Blocks.DARK_OAK_PLANKS);
      BYTE_BLOCKS.add(Blocks.CRIMSON_PLANKS);
      BYTE_BLOCKS.add(Blocks.WARPED_PLANKS);
      BYTE_BLOCKS.add(Blocks.OAK_WOOD);
      BYTE_BLOCKS.add(Blocks.SPRUCE_WOOD);
      BYTE_BLOCKS.add(Blocks.BIRCH_WOOD);
      BYTE_BLOCKS.add(Blocks.JUNGLE_WOOD);
      BYTE_BLOCKS.add(Blocks.ACACIA_WOOD);
      BYTE_BLOCKS.add(Blocks.DARK_OAK_WOOD);
      BYTE_BLOCKS.add(Blocks.CRIMSON_HYPHAE);
      BYTE_BLOCKS.add(Blocks.WARPED_HYPHAE);
      BYTE_BLOCKS.add(Blocks.COAL_ORE);
      BYTE_BLOCKS.add(Blocks.IRON_ORE);
      BYTE_BLOCKS.add(Blocks.COPPER_ORE);
      BYTE_BLOCKS.add(Blocks.GOLD_ORE);
      BYTE_BLOCKS.add(Blocks.REDSTONE_ORE);
      BYTE_BLOCKS.add(Blocks.EMERALD_ORE);
      BYTE_BLOCKS.add(Blocks.LAPIS_ORE);
      BYTE_BLOCKS.add(Blocks.DIAMOND_ORE);
      BYTE_BLOCKS.add(Blocks.DEEPSLATE_COAL_ORE);
      BYTE_BLOCKS.add(Blocks.DEEPSLATE_IRON_ORE);
      BYTE_BLOCKS.add(Blocks.DEEPSLATE_COPPER_ORE);
      BYTE_BLOCKS.add(Blocks.DEEPSLATE_GOLD_ORE);
      BYTE_BLOCKS.add(Blocks.DEEPSLATE_REDSTONE_ORE);
      BYTE_BLOCKS.add(Blocks.DEEPSLATE_EMERALD_ORE);
      BYTE_BLOCKS.add(Blocks.DEEPSLATE_LAPIS_ORE);
      BYTE_BLOCKS.add(Blocks.DEEPSLATE_DIAMOND_ORE);
      BYTE_BLOCKS.add(Blocks.COAL_BLOCK);
      BYTE_BLOCKS.add(Blocks.IRON_BLOCK);
      BYTE_BLOCKS.add(Blocks.COPPER_BLOCK);
      BYTE_BLOCKS.add(Blocks.GOLD_BLOCK);
      BYTE_BLOCKS.add(Blocks.REDSTONE_BLOCK);
      BYTE_BLOCKS.add(Blocks.EMERALD_BLOCK);
      BYTE_BLOCKS.add(Blocks.LAPIS_BLOCK);
      BYTE_BLOCKS.add(Blocks.DIAMOND_BLOCK);
      BYTE_BLOCKS.add(Blocks.NETHERITE_BLOCK);
      BYTE_BLOCKS.add(Blocks.WAXED_EXPOSED_COPPER);
      BYTE_BLOCKS.add(Blocks.WAXED_WEATHERED_COPPER);
      BYTE_BLOCKS.add(Blocks.WAXED_OXIDIZED_COPPER);
      BYTE_BLOCKS.add(Blocks.WAXED_CUT_COPPER);
      BYTE_BLOCKS.add(Blocks.WAXED_EXPOSED_CUT_COPPER);
      BYTE_BLOCKS.add(Blocks.WAXED_WEATHERED_CUT_COPPER);
      BYTE_BLOCKS.add(Blocks.WAXED_OXIDIZED_CUT_COPPER);
      BYTE_BLOCKS.add(Blocks.WHITE_WOOL);
      BYTE_BLOCKS.add(Blocks.ORANGE_WOOL);
      BYTE_BLOCKS.add(Blocks.MAGENTA_WOOL);
      BYTE_BLOCKS.add(Blocks.LIGHT_BLUE_WOOL);
      BYTE_BLOCKS.add(Blocks.YELLOW_WOOL);
      BYTE_BLOCKS.add(Blocks.LIME_WOOL);
      BYTE_BLOCKS.add(Blocks.PINK_WOOL);
      BYTE_BLOCKS.add(Blocks.GRAY_WOOL);
      BYTE_BLOCKS.add(Blocks.LIGHT_GRAY_WOOL);
      BYTE_BLOCKS.add(Blocks.CYAN_WOOL);
      BYTE_BLOCKS.add(Blocks.PURPLE_WOOL);
      BYTE_BLOCKS.add(Blocks.BLUE_WOOL);
      BYTE_BLOCKS.add(Blocks.BROWN_WOOL);
      BYTE_BLOCKS.add(Blocks.GREEN_WOOL);
      BYTE_BLOCKS.add(Blocks.RED_WOOL);
      BYTE_BLOCKS.add(Blocks.BLACK_WOOL);
      BYTE_BLOCKS.add(Blocks.WHITE_GLAZED_TERRACOTTA);
      BYTE_BLOCKS.add(Blocks.ORANGE_GLAZED_TERRACOTTA);
      BYTE_BLOCKS.add(Blocks.MAGENTA_GLAZED_TERRACOTTA);
      BYTE_BLOCKS.add(Blocks.LIGHT_BLUE_GLAZED_TERRACOTTA);
      BYTE_BLOCKS.add(Blocks.YELLOW_GLAZED_TERRACOTTA);
      BYTE_BLOCKS.add(Blocks.LIME_GLAZED_TERRACOTTA);
      BYTE_BLOCKS.add(Blocks.PINK_GLAZED_TERRACOTTA);
      BYTE_BLOCKS.add(Blocks.GRAY_GLAZED_TERRACOTTA);
      BYTE_BLOCKS.add(Blocks.LIGHT_GRAY_GLAZED_TERRACOTTA);
      BYTE_BLOCKS.add(Blocks.CYAN_GLAZED_TERRACOTTA);
      BYTE_BLOCKS.add(Blocks.PURPLE_GLAZED_TERRACOTTA);
      BYTE_BLOCKS.add(Blocks.BLUE_GLAZED_TERRACOTTA);
      BYTE_BLOCKS.add(Blocks.BROWN_GLAZED_TERRACOTTA);
      BYTE_BLOCKS.add(Blocks.GREEN_GLAZED_TERRACOTTA);
      BYTE_BLOCKS.add(Blocks.RED_GLAZED_TERRACOTTA);
      BYTE_BLOCKS.add(Blocks.BLACK_GLAZED_TERRACOTTA);
      BYTE_BLOCKS.add(Blocks.WHITE_CONCRETE);
      BYTE_BLOCKS.add(Blocks.ORANGE_CONCRETE);
      BYTE_BLOCKS.add(Blocks.MAGENTA_CONCRETE);
      BYTE_BLOCKS.add(Blocks.LIGHT_BLUE_CONCRETE);
      BYTE_BLOCKS.add(Blocks.YELLOW_CONCRETE);
      BYTE_BLOCKS.add(Blocks.LIME_CONCRETE);
      BYTE_BLOCKS.add(Blocks.PINK_CONCRETE);
      BYTE_BLOCKS.add(Blocks.GRAY_CONCRETE);
      BYTE_BLOCKS.add(Blocks.LIGHT_GRAY_CONCRETE);
      BYTE_BLOCKS.add(Blocks.CYAN_CONCRETE);
      BYTE_BLOCKS.add(Blocks.PURPLE_CONCRETE);
      BYTE_BLOCKS.add(Blocks.BLUE_CONCRETE);
      BYTE_BLOCKS.add(Blocks.BROWN_CONCRETE);
      BYTE_BLOCKS.add(Blocks.GREEN_CONCRETE);
      BYTE_BLOCKS.add(Blocks.RED_CONCRETE);
      BYTE_BLOCKS.add(Blocks.BLACK_CONCRETE);
      BYTE_BLOCKS.add(Blocks.WHITE_STAINED_GLASS);
      BYTE_BLOCKS.add(Blocks.ORANGE_STAINED_GLASS);
      BYTE_BLOCKS.add(Blocks.MAGENTA_STAINED_GLASS);
      BYTE_BLOCKS.add(Blocks.LIGHT_BLUE_STAINED_GLASS);
      BYTE_BLOCKS.add(Blocks.YELLOW_STAINED_GLASS);
      BYTE_BLOCKS.add(Blocks.LIME_STAINED_GLASS);
      BYTE_BLOCKS.add(Blocks.PINK_STAINED_GLASS);
      BYTE_BLOCKS.add(Blocks.GRAY_STAINED_GLASS);
      BYTE_BLOCKS.add(Blocks.LIGHT_GRAY_STAINED_GLASS);
      BYTE_BLOCKS.add(Blocks.CYAN_STAINED_GLASS);
      BYTE_BLOCKS.add(Blocks.PURPLE_STAINED_GLASS);
      BYTE_BLOCKS.add(Blocks.BLUE_STAINED_GLASS);
      BYTE_BLOCKS.add(Blocks.BROWN_STAINED_GLASS);
      BYTE_BLOCKS.add(Blocks.GREEN_STAINED_GLASS);
      BYTE_BLOCKS.add(Blocks.RED_STAINED_GLASS);
      BYTE_BLOCKS.add(Blocks.BLACK_STAINED_GLASS);
      BYTE_BLOCKS.add(Blocks.WHITE_TERRACOTTA);
      BYTE_BLOCKS.add(Blocks.ORANGE_TERRACOTTA);
      BYTE_BLOCKS.add(Blocks.MAGENTA_TERRACOTTA);
      BYTE_BLOCKS.add(Blocks.LIGHT_BLUE_TERRACOTTA);
      BYTE_BLOCKS.add(Blocks.YELLOW_TERRACOTTA);
      BYTE_BLOCKS.add(Blocks.LIME_TERRACOTTA);
      BYTE_BLOCKS.add(Blocks.PINK_TERRACOTTA);
      BYTE_BLOCKS.add(Blocks.GRAY_TERRACOTTA);
      BYTE_BLOCKS.add(Blocks.LIGHT_GRAY_TERRACOTTA);
      BYTE_BLOCKS.add(Blocks.CYAN_TERRACOTTA);
      BYTE_BLOCKS.add(Blocks.PURPLE_TERRACOTTA);
      BYTE_BLOCKS.add(Blocks.BLUE_TERRACOTTA);
      BYTE_BLOCKS.add(Blocks.BROWN_TERRACOTTA);
      BYTE_BLOCKS.add(Blocks.GREEN_TERRACOTTA);
      BYTE_BLOCKS.add(Blocks.RED_TERRACOTTA);
      BYTE_BLOCKS.add(Blocks.BLACK_TERRACOTTA);
      BYTE_BLOCKS.add(Blocks.STONE_BRICKS);
      BYTE_BLOCKS.add(Blocks.MOSSY_STONE_BRICKS);
      BYTE_BLOCKS.add(Blocks.CRACKED_STONE_BRICKS);
      BYTE_BLOCKS.add(Blocks.CHISELED_STONE_BRICKS);
      BYTE_BLOCKS.add(Blocks.PACKED_MUD);
      BYTE_BLOCKS.add(Blocks.MUD_BRICKS);
      BYTE_BLOCKS.add(Blocks.DEEPSLATE_BRICKS);
      BYTE_BLOCKS.add(Blocks.CRACKED_DEEPSLATE_BRICKS);
      BYTE_BLOCKS.add(Blocks.DEEPSLATE_TILES);
      BYTE_BLOCKS.add(Blocks.CRACKED_DEEPSLATE_TILES);
      BYTE_BLOCKS.add(Blocks.CHISELED_DEEPSLATE);
      BYTE_BLOCKS.add(Blocks.NETHERRACK);
      BYTE_BLOCKS.add(Blocks.SOUL_SAND);
      BYTE_BLOCKS.add(Blocks.SOUL_SOIL);
      BYTE_BLOCKS.add(Blocks.POLISHED_BASALT);
      BYTE_BLOCKS.add(Blocks.SMOOTH_BASALT);
      BYTE_BLOCKS.add(Blocks.COMPOSTER);
      BYTE_BLOCKS.add(Blocks.BARREL);
      BYTE_BLOCKS.add(Blocks.SMOKER);
      BYTE_BLOCKS.add(Blocks.BLAST_FURNACE);
      BYTE_BLOCKS.add(Blocks.CARTOGRAPHY_TABLE);
      BYTE_BLOCKS.add(Blocks.FLETCHING_TABLE);
      BYTE_BLOCKS.add(Blocks.SMITHING_TABLE);
      BYTE_BLOCKS.add(Blocks.CAULDRON);
      BYTE_BLOCKS.add(Blocks.LOOM);
      BYTE_BLOCKS.add(Blocks.CRAFTING_TABLE);
      BYTE_BLOCKS.add(Blocks.FURNACE);
      BYTE_BLOCKS.add(Blocks.BOOKSHELF);
      BYTE_BLOCKS.add(Blocks.SPAWNER);
      BYTE_BLOCKS.add(Blocks.BEACON);
      BYTE_BLOCKS.add(Blocks.ENDER_CHEST);
      BYTE_BLOCKS.add(Blocks.JUKEBOX);
      BYTE_BLOCKS.add(Blocks.BEDROCK);
      BYTE_BLOCKS.add(Blocks.NETHER_GOLD_ORE);
      BYTE_BLOCKS.add(Blocks.NETHER_QUARTZ_ORE);
      BYTE_BLOCKS.add(Blocks.ANCIENT_DEBRIS);
      BYTE_BLOCKS.add(Blocks.RAW_IRON_BLOCK);
      BYTE_BLOCKS.add(Blocks.RAW_COPPER_BLOCK);
      BYTE_BLOCKS.add(Blocks.RAW_GOLD_BLOCK);
      BYTE_BLOCKS.add(Blocks.AMETHYST_BLOCK);
      BYTE_BLOCKS.add(Blocks.BUDDING_AMETHYST);
      BYTE_BLOCKS.add(Blocks.SANDSTONE);
      BYTE_BLOCKS.add(Blocks.CUT_SANDSTONE);
      BYTE_BLOCKS.add(Blocks.CHISELED_SANDSTONE);
      BYTE_BLOCKS.add(Blocks.RED_SANDSTONE);
      BYTE_BLOCKS.add(Blocks.CUT_RED_SANDSTONE);
      BYTE_BLOCKS.add(Blocks.CHISELED_RED_SANDSTONE);
      BYTE_BLOCKS.add(Blocks.GLASS);
      BYTE_BLOCKS.add(Blocks.SMOOTH_QUARTZ);
      BYTE_BLOCKS.add(Blocks.SMOOTH_SANDSTONE);
      BYTE_BLOCKS.add(Blocks.SMOOTH_RED_SANDSTONE);
      BYTE_BLOCKS.add(Blocks.SMOOTH_STONE);
      BYTE_BLOCKS.add(Blocks.BRICKS);
      BYTE_BLOCKS.add(Blocks.SNOW_BLOCK);
      BYTE_BLOCKS.add(Blocks.PACKED_ICE);
      BYTE_BLOCKS.add(Blocks.BLUE_ICE);
      BYTE_BLOCKS.add(Blocks.CLAY);
      BYTE_BLOCKS.add(Blocks.PURPUR_BLOCK);
      BYTE_BLOCKS.add(Blocks.PURPUR_PILLAR);
      BYTE_BLOCKS.add(Blocks.END_STONE);
      BYTE_BLOCKS.add(Blocks.END_STONE_BRICKS);
      BYTE_BLOCKS.add(Blocks.PUMPKIN);
      BYTE_BLOCKS.add(Blocks.NETHER_BRICKS);
      BYTE_BLOCKS.add(Blocks.RED_NETHER_BRICKS);
      BYTE_BLOCKS.add(Blocks.OBSIDIAN);
      BYTE_BLOCKS.add(Blocks.MOSSY_COBBLESTONE);
      BYTE_BLOCKS.add(Blocks.GLOWSTONE);
      BYTE_BLOCKS.add(Blocks.REINFORCED_DEEPSLATE);
      BYTE_BLOCKS.add(Blocks.MELON);
      BYTE_BLOCKS.add(Blocks.SCULK);
      BYTE_BLOCKS.add(Blocks.HAY_BLOCK);
      BYTE_BLOCKS.add(Blocks.BONE_BLOCK);
      BYTE_BLOCKS.add(Blocks.BROWN_MUSHROOM_BLOCK);
      BYTE_BLOCKS.add(Blocks.RED_MUSHROOM_BLOCK);
      BYTE_BLOCKS.add(Blocks.MUSHROOM_STEM);
      BYTE_BLOCKS.add(Blocks.MAGMA_BLOCK);
      BYTE_BLOCKS.add(Blocks.NETHER_WART_BLOCK);
      BYTE_BLOCKS.add(Blocks.WARPED_WART_BLOCK);
      BYTE_BLOCKS.add(Blocks.CHISELED_QUARTZ_BLOCK);
      BYTE_BLOCKS.add(Blocks.QUARTZ_BRICKS);
      BYTE_BLOCKS.add(Blocks.PRISMARINE);
      BYTE_BLOCKS.add(Blocks.PRISMARINE_BRICKS);
      BYTE_BLOCKS.add(Blocks.DARK_PRISMARINE);
      BYTE_BLOCKS.add(Blocks.SEA_LANTERN);
      BYTE_BLOCKS.add(Blocks.NOTE_BLOCK);
      BYTE_BLOCKS.add(Blocks.OCHRE_FROGLIGHT);
      BYTE_BLOCKS.add(Blocks.VERDANT_FROGLIGHT);
      BYTE_BLOCKS.add(Blocks.PEARLESCENT_FROGLIGHT);
      BYTE_BLOCKS.add(Blocks.SHROOMLIGHT);
      BYTE_BLOCKS.add(Blocks.CRYING_OBSIDIAN);
      BYTE_BLOCKS.add(Blocks.BLACKSTONE);
      BYTE_BLOCKS.add(Blocks.POLISHED_BLACKSTONE);
      BYTE_BLOCKS.add(Blocks.CHISELED_POLISHED_BLACKSTONE);
      BYTE_BLOCKS.add(Blocks.POLISHED_BLACKSTONE_BRICKS);
      BYTE_BLOCKS.add(Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS);
      BYTE_BLOCKS.add(Blocks.GILDED_BLACKSTONE);
      BYTE_BLOCKS.add(Blocks.BEE_NEST);
      BYTE_BLOCKS.add(Blocks.BEEHIVE);
      BYTE_BLOCKS.add(Blocks.HONEYCOMB_BLOCK);
      BYTE_BLOCKS.add(Blocks.TARGET);
      BYTE_BLOCKS.add(Blocks.LODESTONE);
      BYTE_BLOCKS.add(Blocks.RESPAWN_ANCHOR);
      BYTE_BLOCKS.add(Blocks.DRIED_KELP_BLOCK);
      BYTE_BLOCKS.add(Blocks.REDSTONE_LAMP);
      BYTE_BLOCKS.add(Blocks.SLIME_BLOCK);
      BYTE_BLOCKS.add(Blocks.HONEY_BLOCK);
      BYTE_BLOCKS.add(Blocks.OBSERVER);
      BYTE_BLOCKS.add(Blocks.DISPENSER);
      BYTE_BLOCKS.add(Blocks.DROPPER);
      BYTE_BLOCKS.add(Blocks.LECTERN);
      BYTE_BLOCKS.add(Blocks.COMMAND_BLOCK);
      BYTE_BLOCKS.add(Blocks.COBWEB);
   }
}
