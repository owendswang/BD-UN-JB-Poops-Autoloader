## ps5_autoload.elf

Inspired by [itsPLK/ps5_y2jb_autoloader](https://github.com/itsPLK/ps5_y2jb_autoloader) and [itsPLK/ps5_lua_autoloader](https://github.com/itsPLK/ps5_lua_autoloader).  
It reads from `/data/ps5_autoloader`, then `/mnt/USB?/ps5_autoloader`, then `/mnt/disc/ps5_autoloader`.

## Building

Built with '[ps5-payload-dev/pacbrew-repo](https://github.com/ps5-payload-dev/pacbrew-repo)'. (You might need to rebuild it by the source code of it. Because current release v0.32 is not built with the latest SDK which supports upto FW 12.00. Go check the 'Actions' section of it for detailed building procedure.)
