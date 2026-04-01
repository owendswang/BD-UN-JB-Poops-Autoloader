## 'ps5_autoloader' folder

Put payloads in it. Then put this folder to `/mnt/USB?/` or `/data/`. Or implement it into BD disc root path. It could read `/mnt/disc/ps5_autoloader` too.

## 'autoload.txt' file

It's the script file to define payloads to run automatically by Autoloader. Edit this file according to the example in it.

### Example
```
ftpsrv-ps5-1.15-ng-beta9.elf
!1000
kstuff-lite-1.1-dr-stable.elf
!1000
shadowmountplus-1.6test7-fix2.elf
```
