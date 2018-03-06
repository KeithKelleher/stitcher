#!/bin/bash
timestamp="$(date +'%Y%m%d-%H%M%S')"
db="stitchv$timestamp.db"
log="log$timestamp.txt"

#keep track of current time
curr_time=$(date +%s)

echo $(date) > $log
sbt stitcher/"runMain ncats.stitcher.impl.LineMoleculeEntityFactory $db data/withdrawn.conf"
echo 'Withdrawn:' $(( ($(date +%s) - $curr_time )/60 )) 'min' >> $log
sbt stitcher/"runMain ncats.stitcher.impl.LineMoleculeEntityFactory $db data/broad.conf"
echo 'Broad:' $(( ($(date +%s) - $curr_time )/60 )) 'min' >> $log
sbt stitcher/"runMain ncats.stitcher.impl.LineMoleculeEntityFactory $db data/ruili.conf"
echo 'Ruili:' $(( ($(date +%s) - $curr_time )/60 )) 'min' >> $log
sbt stitcher/"runMain ncats.stitcher.impl.SRSJsonEntityFactory $db \"name=G-SRS, February 2018\" cache=data/hash.db data/dump-public-2018-02-06.gsrs"
echo 'gsrs:' $(( ($(date +%s) - $curr_time )/60 )) 'min' >> $log
sbt stitcher/"runMain ncats.stitcher.impl.RanchoJsonEntityFactory $db \"name=Rancho BioSciences, February 2018\" cache=data/hash.db data/rancho-export_2018-02-20_22-20.json"
echo 'rancho:' $(( ($(date +%s) - $curr_time )/60 )) 'min' >> $log
sbt stitcher/"runMain ncats.stitcher.impl.NPCEntityFactory $db \"name=NCATS Pharmaceutical Collection, April 2012\" cache=data/hash.db ../inxight-planning/files/npc-dump-1.2-04-25-2012_annot.sdf.gz"
echo 'NPC:' $(( ($(date +%s) - $curr_time )/60 )) 'min' >> $log
sbt stitcher/"runMain ncats.stitcher.impl.PharmManuEncyl3rdEntityFactory $db \"name=Pharmaceutical Manufacturing Encyclopedia (Third Edition)\" ../inxight-planning/files/PharmManuEncycl3rdEd.json"
echo 'PharmManuEncycl:' $(( ($(date +%s) - $curr_time )/60 )) 'min' >> $log
sbt stitcher/"runMain ncats.stitcher.impl.DrugBankXmlEntityFactory $db \"name=DrugBank, November 2017\" cache=data/hash.db ../inxight-planning/files/drugbank_all_full_database.xml.zip"
echo 'DrugBank:' $(( ($(date +%s) - $curr_time )/60 )) 'min' >> $log

# these add additional data for event calculator
sbt stitcher/"runMain ncats.stitcher.impl.MapEntityFactory $db data/dailymedrx.conf"
echo 'DailyMedRx:' $(( ($(date +%s) - $curr_time )/60 )) 'min' >> $log
sbt stitcher/"runMain ncats.stitcher.impl.MapEntityFactory $db data/dailymedrem.conf"
echo 'DailyMedRem:' $(( ($(date +%s) - $curr_time )/60 )) 'min' >> $log
sbt stitcher/"runMain ncats.stitcher.impl.MapEntityFactory $db data/dailymedotc.conf"
echo 'DailyMedOTC:' $(( ($(date +%s) - $curr_time )/60 )) 'min' >> $log
sbt stitcher/"runMain ncats.stitcher.impl.MapEntityFactory $db data/ob.conf"
echo 'OB:' $(( ($(date +%s) - $curr_time )/60 )) 'min' >> $log
sbt stitcher/"runMain ncats.stitcher.impl.MapEntityFactory $db data/ct.conf"
echo 'CT:' $(( ($(date +%s) - $curr_time )/60 )) 'min' >> $log

# now the stitching..
sbt stitcher/"runMain ncats.stitcher.tools.CompoundStitcher $db 1"
echo 'Stitching:' $(( ($(date +%s) - $curr_time )/60 )) 'min' >> $log
# calculate events
sbt stitcher/"runMain ncats.stitcher.calculators.EventCalculator $db 1"
echo 'EventCalculator:' $(( ($(date +%s) - $curr_time )/60 )) 'min' >> $log
echo $(date) >> $log
