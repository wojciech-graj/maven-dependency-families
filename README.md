# Dependency Families in the Maven Ecosystem: An Analysis of Software Dependency Graphs

This repository contains all the source code for the "Dependency Families in the Maven Ecosystem: An Analysis of Software Dependency Graphs" thesis for the BSc. CSE at TU Delft.

https://resolver.tudelft.nl/67d109a8-e7b6-45eb-995c-f2cf8851ac69

## Usage

Note that >24 hours will be required to generate the dataset. It is recommended to make >16 GB RAM available to Postgresql. Cargo (Rust), Maven (Java), Python 3, and Postgresql are needed.

All java programs that interact with the database have `DB_*` variables in their source files that must be set by the user. The Rust programs rely on the `DATABASE_URL` environment variable.

The `sql/analyze.sql` file contains various queries used to analyze the data. Some of these queries produce CSV files that can be used by the plotter.

The following commands can be executed to obtain the dataset.

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
cd versioncheck && mvn exec:java && cd ..
psql -d <DATABASE> -f sql/import_4.sql
cd scraper && cargo run --release && cd ..
psql -d <DATABASE> -f sql/import_5.sql
cd dependencies && mvn exec:java && cd ..
psql -d <DATABASE> -f sql/import_6.sql
cd cluster && mvn exec:java && cd ..
psql -d <DATABASE> -f sql/import_7.sql
```

## Software Components

- lucene-csv-export: Convert a lucene file into a csv
- bundleimport: Extract OSGi metadata from lucene import
- versioncheck: Identify semver-compatible versions
- scraper: Download POM files for all releases
- dependencies: Identify dependency relations and parent-child relations between artifacts
- detect: Identify best parameters for community detection with a grid search
- cluster: Identify dependency families
- version_analyzer: Calculate version homogeneity scores
- plotter: Create plots from csv files

## License

```
    Copyright (C) 2025  Wojciech Graj

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
```
