# cs377-pagewalk-mod
### My final project for Operating Systems is a Minecraft Mod that visualizes a three level page table.

Most of the files you see above are the gradle scripts for building the code yourself.
The code of interest can be found in [[this]](https://github.com/borisshoes/cs377-pagewalk-mod/tree/main/src/main/java/net/borisshoes/multilevelpagetablevisualizer) folder

There are 2 classes that pertain to my project: `MultiLevelPageTableVisualizer.java` and `ThreeLevelTable.java`.
The `GenericTimer.java` class was something I added in case I need it for future additions, but is currently unused.

Both of the classes are commented, explaining what pretty much everything does and why it's there.
`ThreeLevelTable.java` contains most of the OS related code. It is what generates and stores the page tables and memory.
`ThreeLevelTable.java` also has code that places the blocks in game to create the in world display from the values in the data structures.
`MultiLevelPageTableVisualizer.java` is most of the backend Minecraft related code, setting up commands, mapping blocks to byte values, and generating the HUD display.

To discuss a bit about the implementation of the page table, the system uses 16-bit addresses.
This means that each level of the table uses 4 bits for the table index, and a final 4 for the physical offset.
The way I designed the system is that all the tables are stored in a separate structure than physical memory.
While this is not how an actual OS works, which usually stores the in use tables in main memory and the rest in disk; adding the code to handle a table cache and separate 'disk' and all the operations to switch tables from memory to disk was beyond the scope of the project.
The point of this project is to visualize how the table system dissects the address and finds the next entry in the next-level table, which works the same with this different implementation as it would in a real OS, just pointing to different data structures.

When memory is initialized in game, all page table pointers are assigned, which means that all tables are instantly generated.
This is done using a Depth-First-Search style traversal that makes the tree-like structure of the 3 levels of tables which will assign their pointers to random empty addresses in the table storage.
Memory is also initialized to random values.
This means that while page-walking you should never come across a page of all 0s.
However, because the table data structure is bigger than needed to hold all of the tables, if you manually look through the tables, you will find many table pages of 0s.

To install the mod, you must use the Fabric Mod Loader for Minecraft 1.19.3. 
You can find the .jar file in the releases section of the repo.
For those unfamiliar with using Fabric Mods in Minecraft you can follow [[this easy tutorial]](https://www.youtube.com/watch?v=eHl8gwOlN5U&pp=ygUkYWJmaWVsZGVyIGhvdyB0byBpbnN0YWxsIGZhYnJpYyBtb2Rz)

Once installed, you can load up a single player world with cheats enabled.
* `/mem init` will initialize memory, and must be done before using any other commands. It will also generate the display box.
* `/mem delete` will delete memory and clear any display that has been generated
* `/mem print` will dump generated memory and tables to the console, which won't be visible unless you have debug output enabled from the Minecraft launcher
* `/mem load [page/table] <address>` will load a table or page in memory. Memory has 4096 pages, and there are 8192 spaces in table storage. Example `/mem load table 5812`. **Addresses can be in base 16, 10, or 2 (0xa5e2, 3424, 0b1011) are all valid**
* `/mem pagewalk <address>` will do an automatic page walk to the spot in memory that it directs to. Addresses here are 16 bits. Example: `/mem pagewalk 0x482b`
* `/mem pagewalk start <address>` will start a step-by-step page walk through all 3 levels to the given address.
* `/mem pagewalk step` will proceed to the next step in the page walk after it has been started. 

Looking at blocks within the display will give you a head-up-display of what bit/byte values they correspond to. 
At the top of the display is 4, 4-block sections which shows the address during a page walk. 
Each section will be highlighted in light-colored brick to show which bits are being used as the index.
In the 4x4 grid of the display is where tables and pages are shown.
The area highlighted with light-colored brick is the entry that is being selected for by the highlighted index/offset bits.
The two blocks below and to the left of the address is the byte representation of the address.

All of this and more is explained in my 10-minute presentation video. [[Watch the video]](https://youtu.be/a628P-KB8SQ)