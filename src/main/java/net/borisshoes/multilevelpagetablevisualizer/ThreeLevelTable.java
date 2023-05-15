package net.borisshoes.multilevelpagetablevisualizer;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.text.LiteralTextContent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Pair;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;

import static net.borisshoes.multilevelpagetablevisualizer.MultiLevelPageTableVisualizer.BYTE_BLOCKS;

public class ThreeLevelTable {
   
   // 16-bit address: WWWW XXXX YYYY ZZZZ
   // W -> 4-bit page global directory (PGD) offset
   // X -> 4-bit page middle directory (PMD) offset
   // Y -> 4-bit page table entry (PTE) offset
   // Z -> 4-bit physical page offset
   
   // Normally the PGD,PMD and PTE would have the relevant tables in memory, with some going to disk, but here I'm storing them separately
   public static final int PTBR = 272; // Starting point of the PGD
   public short[] tables = new short[8192]; // Stores all the page tables
   public byte[][] mem = new byte[4096][16]; // Physical memory
   
   // World related stuff
   public final ServerWorld world;
   public final BlockPos pos; // Base position for the in world display
   public int addrSel = -1; // Current address section highlighted
   public int pageSel = -1; // Current page entry highlighted
   public short curAddr = -1; // Current address being shown
   public boolean pagewalking = false; // Is a page walk active
   
   public ThreeLevelTable(ServerWorld world, BlockPos pos){
      this.world = world;
      this.pos = pos;
      
      // ======== Memory Stuff ========
      ArrayList<Short> available = new ArrayList<>();
      for(short i = 0; i < mem.length; i++){
         available.add(i);
      }
      
      // Set up PGD
      for(int i = PTBR; i < PTBR+16; i++){
         short rand1,addr1;
         do{
            rand1 = ((short)(Math.random()*512));
            addr1 = (short) (rand1*16);
         }while(i == addr1 || tables[addr1] != 0x0);
         //System.out.printf("Inserting addr1: %x at %d\n",addr1,i);
         tables[i] = (short) ((addr1 << 3) | 0x1);
         
         // Set up PMD
         for(int j = addr1; j < addr1+16; j++){
            short rand2,addr2;
            do{
               rand2 = ((short)(Math.random()*512));
               addr2 = (short) (rand2*16);
            }while(j == addr2 || tables[addr2] != 0x0);
            //System.out.printf("Inserting addr2: %x at %d\n",addr2,j);
            tables[j] = (short) ((addr2 << 3) | 0x3);
            
            // Set up PTE
            for(int k = addr2; k < addr2+16; k++){
               int ind = (int) (Math.random()*available.size());
               short addr3 = available.remove(ind);
               tables[k] = (short) ((addr3 << 4) | 0x7);
            }
         }
      }
      
      // Initialize memory
      for(int i = 0; i < mem.length; i++){
         for(int j = 0; j < mem[0].length; j++){
            mem[i][j] = ((byte)(Math.random()*256));
         }
      }
   }
   
   // Adds the in world display
   public void constructBox(){
      for(int x = -13; x <= 13; x++){
         for(int y = -8; y <= 8; y++){
            for(int z = -8; z <= 8; z++){
               BlockPos block = pos.mutableCopy().add(x,y,z);
               BlockState state = world.getBlockState(block);
               
               if(x == -13 || x == 13 || y == -8 || y == 8 || z == -8 || z == 8){ // Box bounds
                  world.setBlockState(block,Blocks.BLACK_CONCRETE.getDefaultState());
               }else{
                  world.setBlockState(block,Blocks.AIR.getDefaultState());
               }
            }
         }
      }
      // Make address and page backdrops
      createOutlines(true);
      createOutlines(false);
   }
   
   // Removes the in world display
   public void deleteBox(){
      for(int x = -13; x <= 13; x++){
         for(int y = -8; y <= 8; y++){
            for(int z = -8; z <= 8; z++){
               BlockPos block = pos.mutableCopy().add(x,y,z);
               world.setBlockState(block, Blocks.AIR.getDefaultState());
            }
         }
      }
   }
   
   // Makes the backdrop of the display in world
   public void createOutlines(boolean addrBar){
      int addrSelX = 10 - 5*addrSel;
      int pageSelX = 6 - 3*(pageSel%4);
      int pageSelY = 2 - 2*(pageSel/4);
      
      if(addrBar){
         for(int x = -10; x <= 10; x++){
            for(int y = 0; y <= 5; y++){
               BlockPos block = pos.mutableCopy().add(x,y,7);
               
               if(addrSel >= 0 && y >= 3 && x >= addrSelX-5 && x <= addrSelX){ // Highlight address part
                  world.setBlockState(block,Blocks.END_STONE_BRICK_WALL.getDefaultState());
               }else if(y <= 2 && x >= 7){ // Byte addr bounds
                  world.setBlockState(block,Blocks.COBBLESTONE_WALL.getDefaultState());
               }else if(y >= 3){ // Display panel bounds
                  world.setBlockState(block,Blocks.POLISHED_BLACKSTONE_BRICK_WALL.getDefaultState());
               }
            }
         }
      }else{
         for(int x = -10; x <= 10; x++){
            for(int y = -6; y <= 2; y++){
               BlockPos block = pos.mutableCopy().add(x,y,7);
               
               if(pageSel >= 0 &&y >= pageSelY-2 && y <= pageSelY && x >= pageSelX-3 && x <= pageSelX){ // Highlight page part
                  world.setBlockState(block,Blocks.END_STONE_BRICK_WALL.getDefaultState());
               }else if(y < 0 || x <= 6){ // Display panel bounds
                  world.setBlockState(block,Blocks.POLISHED_BLACKSTONE_BRICK_WALL.getDefaultState());
               }
            }
         }
      }
      
   }
   
   // Shows an address in world, with an optional section highlighted
   public void displayAddr(short addr, int sel){
      addrSel = sel;
      curAddr = addr;
      createOutlines(true); // Build the backdrop
      
      BlockPos addrStartPos = pos.mutableCopy().add(-9,4,7);
      BlockPos addrPos = addrStartPos.mutableCopy(); // 'Block pointer' for writing blocks to the world
      for(int i = 0; i < 16; i++){ // Display each bit
         int bit = (addr & (1 << i)) >> i; // Bit mask
         if(bit == 1){
            world.setBlockState(addrPos, Blocks.WHITE_CONCRETE_POWDER.getDefaultState()); // White concrete dust is a 1
         }else{
            world.setBlockState(addrPos, Blocks.GRAY_CONCRETE_POWDER.getDefaultState()); // Gray concrete dust is a 0
         }
         addrPos = addrPos.add(i%4==3 ? 2 : 1,0,0); // Moves the 'block pointer' forward, dividing into 4 sections of 4 bits
      }
      
      // Display the byte version of the address with block encoding
      world.setBlockState(pos.mutableCopy().add(9,1,7), BYTE_BLOCKS.get((addr & 0xFF00) >>> 8).getDefaultState());
      world.setBlockState(pos.mutableCopy().add(8,1,7), BYTE_BLOCKS.get(addr & 0xFF).getDefaultState());
   }
   
   // Shows a table in world, with an optional entry highlighted
   public void displayTablePage(short pageStart, int sel){
      pageSel = sel;
      createOutlines(false); // Build the backdrop
      BlockPos entryStartPos = pos.mutableCopy().add(-5,-5,7);
      BlockPos entryPos = entryStartPos.mutableCopy(); // 'Block pointer' for writing blocks to the world
      for(int i = 0; i < 16; i++){ // Display all 16 2-byte entries
         short entry = tables[pageStart+(15-i)];
         
         world.setBlockState(entryPos, BYTE_BLOCKS.get(entry & 0xFF).getDefaultState()); // Lower byte
         entryPos = entryPos.add(1,0,0);
         
         world.setBlockState(entryPos, BYTE_BLOCKS.get((entry & 0xFF00) >>> 8).getDefaultState()); // Upper byte
         entryPos = entryPos.add(i%4 == 3 ? -10 : 2,i%4 == 3 ? 2 : 0,0); // Move the block pointer in a 4x4 pattern
      }
   }
   
   // Shows a page of memory in world, with an optional entry highlighted
   public void displayMemPage(byte[] page, int sel){
      pageSel = sel;
      createOutlines(false); // Build the backdrop
      BlockPos entryStartPos = pos.mutableCopy().add(-5,-5,7);
      BlockPos entryPos = entryStartPos.mutableCopy(); // 'Block pointer' for writing blocks to the world
      for(int i = 0; i < 16; i++){ // Display all 16 2-byte entries
         byte entry = page[15-i]; // Display entries from top left to bottom right, but build from bottom right to top left
         
         world.setBlockState(entryPos, BYTE_BLOCKS.get(entry & 0xFF).getDefaultState()); // Byte being stored
         entryPos = entryPos.add(1,0,0);
         
         // Physical pages are accessed 1 byte at a time, so this block is filled with a 'filler block'
         world.setBlockState(entryPos, Blocks.CONDUIT.getDefaultState().with(Properties.WATERLOGGED,false));
         entryPos = entryPos.add(i%4 == 3 ? -10 : 2,i%4 == 3 ? 2 : 0,0); // Move the block pointer in a 4x4 pattern
      }
   }
   
   
   // ======== Memory Stuff ========
   
   // Dumps the table array to console
   public void printTables(){
      for(int i = 0; i < tables.length; i++){
         if(tables[i] != 0){
            System.out.printf("[%d] %x\n",i,tables[i]);
         }
      }
   }
   
   // Dumps the memory array to console
   public void printMemory(){
      for(int i = 0; i < mem.length; i++){
         System.out.printf("[%d] ",i);
         for(int j = 0; j < mem[0].length; j++){
            System.out.printf("%x ",mem[i][j]);
         }
         System.out.println();
      }
   }
   
   // These functions are adapted from the page walk lab, upgraded for 16 bit addresses
   private short virt_to_pgd(int pfn, short vaddr){
      int offset = (vaddr & 0xF000) >>> 12;
      short pmd = tables[pfn+offset];
      if((pmd & 0x1) == 1){
         return (short) ((pmd & 0xffff) >>> 3);
      }
      return -1;
   }
   
   // These functions are adapted from the page walk lab, upgraded for 16 bit addresses
   private short virt_to_pmd(int pfn, short vaddr){
      int offset = (vaddr & 0x0F00) >>> 8;
      short pte = tables[pfn+offset];
      if((pte & 0x3) == 3){
         return (short) ((pte & 0xffff) >>> 3);
      }
      return -1;
   }
   
   // These functions are adapted from the page walk lab, upgraded for 16 bit addresses
   private short virt_to_pte(int pfn, short vaddr){
      int offset = (vaddr & 0x00F0) >>> 4;
      short frame = tables[pfn+offset];
      if((frame & 0x7) == 7){
         return (short) ((frame & 0xffff) >>> 4);
      }
      return -1;
   }
   
   // These functions are adapted from the page walk lab, upgraded for 16 bit addresses
   private byte virt_to_phys(int pfn, short vaddr){
      int offset = (vaddr & 0x000F);
      return mem[pfn][offset];
   }
   
   // This function steps the currently active page walk so each step can be displayed to the player
   public void pageWalkStep(ServerPlayerEntity player){
      player.networkHandler.sendPacket(new TitleFadeS2CPacket(5, 40, 10));
      player.networkHandler.sendPacket(new TitleS2CPacket(MutableText.of(new LiteralTextContent("."))
            .formatted(Formatting.LIGHT_PURPLE)));
      if(addrSel == 0){ // First step, show the PGD
         int offset = (curAddr & 0xF000) >>> 12;
         short addr = (short) PTBR;
         displayTablePage(addr,offset);
         player.networkHandler.sendPacket(new SubtitleS2CPacket(MutableText.of(new LiteralTextContent("Loading PGD at address: 0x"+Integer.toHexString(addr)))
               .formatted(Formatting.AQUA)));
         player.sendMessage(Text.literal("PGD Address: 0x"+Integer.toHexString(addr & 0xFFFF)).formatted(Formatting.GREEN),false);
      }else if(addrSel == 1){ // Second step, show the PMD
         int offset = (curAddr & 0x0F00) >>> 8;
         short addr = virt_to_pgd(PTBR, curAddr);
         displayTablePage(addr,offset);
         player.networkHandler.sendPacket(new SubtitleS2CPacket(MutableText.of(new LiteralTextContent("Loading PMD at address: 0x"+Integer.toHexString(addr)))
               .formatted(Formatting.AQUA)));
         player.sendMessage(Text.literal("PMD Address: 0x"+Integer.toHexString(addr & 0xFFFF)).formatted(Formatting.GREEN),false);
      }else if(addrSel == 2){ // Third step, show the PTE
         int offset = (curAddr & 0x00F0) >>> 4;
         short addr = virt_to_pmd(virt_to_pgd(PTBR, curAddr),curAddr);
         displayTablePage(addr,offset);
         player.networkHandler.sendPacket(new SubtitleS2CPacket(MutableText.of(new LiteralTextContent("Loading PTE at address: 0x"+Integer.toHexString(addr)))
               .formatted(Formatting.AQUA)));
         player.sendMessage(Text.literal("PTE Address: 0x"+Integer.toHexString(addr & 0xFFFF)).formatted(Formatting.GREEN),false);
      }else if(addrSel == 3){ // Fourth step, show the page in memory
         int offset = (curAddr & 0x000F);
         short addr = virt_to_pte(virt_to_pmd(virt_to_pgd(PTBR, curAddr),curAddr),curAddr);
         displayMemPage(mem[addr],offset);
         player.networkHandler.sendPacket(new SubtitleS2CPacket(MutableText.of(new LiteralTextContent("Loading Memory Page at address: 0x"+Integer.toHexString(addr)))
               .formatted(Formatting.AQUA)));
         player.sendMessage(Text.literal("Memory Address: 0x"+Integer.toHexString(addr & 0xFFFF)).formatted(Formatting.GREEN),false);
      }
   }
   
   // This code is from my solution to the page walk lab it was my starting point for making this implementation
   public byte pageWalkByte(short vaddr){
      short pmd = virt_to_pgd(PTBR, vaddr);
      if (pmd == -1) {
         MultiLevelPageTableVisualizer.logger.warn("\tsegfault: Page Middle Directory does not exist");
         return 0;
      }
      //MultiLevelPageTableVisualizer.logger.warn("PMD: "+pmd);
      
      // 2) get the PTE address
      short pte = virt_to_pmd(pmd, vaddr);
      if (pte == -1) {
         MultiLevelPageTableVisualizer.logger.warn("\tsegfault: Page Table Entry does not exist");
         return 0;
      }
      //MultiLevelPageTableVisualizer.logger.warn("PTE: "+pte);
      
      // 3) get the frame number
      short phs = virt_to_pte(pte, vaddr);
      if (phs == -1) {
         MultiLevelPageTableVisualizer.logger.warn("\tsegfault: Invalid physical memory");
         return 0;
      }
      //MultiLevelPageTableVisualizer.logger.warn("Phys: "+phs);
      
      // 4) get the value at that location
      byte val = virt_to_phys(phs, vaddr);
      
      return val;
   }
   
   // This page walk function stops at the page in memory so the whole page can be displayed with the desired byte highlighted
   public Pair<Integer,Integer> pageWalkPage(short vaddr){
      short pmd = virt_to_pgd(PTBR, vaddr);
      if (pmd == -1) {
         MultiLevelPageTableVisualizer.logger.warn("\tsegfault: Page Middle Directory does not exist");
         return new Pair<>(-1,-1);
      }
      //MultiLevelPageTableVisualizer.logger.warn("PMD: "+pmd);
      
      // 2) get the PTE address
      short pte = virt_to_pmd(pmd, vaddr);
      if (pte == -1) {
         MultiLevelPageTableVisualizer.logger.warn("\tsegfault: Page Table Entry does not exist");
         return new Pair<>(-1,-1);
      }
      //MultiLevelPageTableVisualizer.logger.warn("PTE: "+pte);
      
      // 3) get the frame number
      short phs = virt_to_pte(pte, vaddr);
      if (phs == -1) {
         MultiLevelPageTableVisualizer.logger.warn("\tsegfault: Invalid physical memory");
         return new Pair<>(-1,-1);
      }
      //MultiLevelPageTableVisualizer.logger.warn("Phys: "+phs);
      
      return new Pair<>((int)phs,(vaddr & 0x000F));
   }
}
