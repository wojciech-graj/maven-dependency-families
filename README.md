```sh
wget https://repo.maven.apache.org/maven2/.index/nexus-maven-repository-index.gz
wget https://repo1.maven.org/maven2/org/apache/maven/indexer/indexer-cli/7.1.5/indexer-cli-7.1.5-cli.jar
java -jar indexer-cli-7.1.5-cli.jar --unpack nexus-maven-repository-index.gz --destination central-lucene-index --type full
cd lucene-csv-export && mvn exec:java -Dexec.args="../central-lucene-index ../out.csv" && cd ..
psql -d <DATABASE> -f sql/import_1.sql
psql -d <DATABASE> -c "\copy imported FROM 'out.csv' DELIMITER ',' CSV"
psql -d <DATABASE> -f sql/import_2.sql
cd bundleimport && mvn exec:java && cd ..
psql -d <DATABASE> -f sql/import_3.sql
```
