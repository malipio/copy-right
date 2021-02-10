FROM groovy:3.0-jre11

USER root
RUN apt-get clean && apt-get update && \
    apt-get install -y git

COPY copyrights.groovy /home/groovy/scripts/
RUN chown -R groovy:groovy /home/groovy/scripts

USER groovy
# running command to fetch grapes in build phase,
# grape.root changed not to clash with anonymous volume provided by parent
RUN groovy -Dgroovy.grape.report.downloads=true -Dgrape.root=/home/groovy/static-deps /home/groovy/scripts/copyrights.groovy --help || echo 'Ignoring failure'
ENTRYPOINT ["groovy", "-Dgrape.root=/home/groovy/static-deps", "/home/groovy/scripts/copyrights.groovy"]
CMD "--help"