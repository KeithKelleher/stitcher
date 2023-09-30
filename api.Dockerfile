FROM openjdk:8-jdk as buildContainer

RUN mkdir /opt/app
COPY . /opt/app

WORKDIR "/opt/app"

RUN apt update
RUN apt-get update
RUN apt-get install sudo
RUN apt-get install nano
RUN sudo apt-get install zip
RUN apt-get install apt-transport-https curl gnupg -yqq
RUN echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" | sudo tee /etc/apt/sources.list.d/sbt.list
RUN echo "deb https://repo.scala-sbt.org/scalasbt/debian /" | sudo tee /etc/apt/sources.list.d/sbt_old.list
RUN curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | sudo -H gpg --no-default-keyring --keyring gnupg-ring:/etc/apt/trusted.gpg.d/scalasbt-release.gpg --import
RUN chmod 644 /etc/apt/trusted.gpg.d/scalasbt-release.gpg

RUN sudo apt-get install pip -y
RUN sudo pip install requests

RUN apt-get update
RUN apt-get install sbt
EXPOSE 9003

RUN echo "-J-Xms2048M -J-Xmx32G -J-Xss1024M -J-XX:+CMSClassUnloadingEnabled -J-XX:+UseConcMarkSweepGC -J-XX:+HeapDumpOnOutOfMemoryError -J-XX:HeapDumpPath=./heapdump.hprof" > .sbtopts

RUN unzip -o scripts/deployment/*zip

RUN chmod +x ./scripts/deployment/restart-stitcher-from-repo.sh

CMD    ./scripts/deployment/restart-stitcher-from-repo.sh /opt/app/apiDB;

# python3 scripts/stitcher-curation/dumpCurations.py prod --outfile scripts/stitcher-curation/dbCurations-{date}.txt
# python3 scripts/stitcher-curation/applyCurations.py dev --filename scripts/stitcher-curation/dbCurations-{date}.txt
# python3 scripts/stitcher-curation/applyCurations.py docker --filename scripts/stitcher-curation/dbCurations-2023-02-13.txt