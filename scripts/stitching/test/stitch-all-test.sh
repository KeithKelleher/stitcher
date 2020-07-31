#!/bin/bash

db="stitchvTEST.db"

#keep track of current time
curr_time=$(date +%s)

echo $(date) > log.txt

sbt stitcher/"runMain ncats.stitcher.impl.SRSJsonEntityFactory $db \"name=G-SRS, April 2020\" cache=data/hash.db ../stitcher-rawinputs/files/dump-public-2020-04-28.gsrs"
#sbt stitcher/"runMain ncats.stitcher.impl.MapEntityFactory stitchvTEST.db data/ob.conf"
#sbt stitcher/"runMain ncats.stitcher.tools.CompoundStitcher $db 1"
#sbt stitcher/"runMain ncats.stitcher.calculators.EventCalculator $db 1"

echo $(date) >> log.txt