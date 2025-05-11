CREATE INDEX idx_dependencies_from_version_id ON dependencies(from_version_id);

CREATE INDEX idx_dependencies_to_artifact_id ON dependencies(to_artifact_id);

CREATE INDEX idx_dependencies_from_to ON dependencies(from_version_id, to_artifact_id);

CREATE INDEX idx_parents_from_artifact_id ON parents(from_artifact_id);

CREATE INDEX idx_parents_to_artifact_id ON parents(to_artifact_id);

DELETE FROM parents
WHERE EXISTS (
        SELECT
            1
        FROM
            parents AS other
        WHERE
            other.from_artifact_id = parents.to_artifact_id
            AND other.to_artifact_id = parents.from_artifact_id);

CREATE TABLE artifact_dependency_counts(
    from_artifact_id INTEGER NOT NULL REFERENCES artifacts(id),
    to_artifact_id INTEGER NOT NULL REFERENCES artifacts(id),
    cnt INTEGER NOT NULL,
    PRIMARY KEY (from_artifact_id, to_artifact_id)
);

INSERT INTO artifact_dependency_counts(from_artifact_id, to_artifact_id, cnt)
SELECT
    versions.artifact_id AS from_artifact_id,
    dependencies.to_artifact_id AS to_artifact_id,
    count(*) AS cnt
FROM
    dependencies
    JOIN versions ON versions.id = dependencies.from_version_id
GROUP BY
    versions.artifact_id,
    dependencies.to_artifact_id;

CREATE INDEX idx_artifact_dependency_counts_from_artifact_id ON artifact_dependency_counts(from_artifact_id);

CREATE INDEX idx_artifact_dependency_counts_to_artifact_id ON artifact_dependency_counts(to_artifact_id);

CREATE TABLE mins(
    a_artifact_id INTEGER NOT NULL REFERENCES artifacts(id),
    b_artifact_id INTEGER NOT NULL REFERENCES artifacts(id),
    cnt INTEGER NOT NULL
);

INSERT INTO mins(a_artifact_id, b_artifact_id, cnt)
SELECT
    a.to_artifact_id AS a_artifact_id,
    b.to_artifact_id AS b_artifact_id,
    least(min(a.cnt), min(b.cnt)) AS cnt
FROM
    artifact_dependency_counts AS a
    JOIN artifact_dependency_counts AS b ON b.from_artifact_id = a.from_artifact_id
WHERE
    a.to_artifact_id < b.to_artifact_id
GROUP BY
    a.from_artifact_id,
    a.to_artifact_id,
    b.to_artifact_id;

CREATE INDEX idx_mins_a_b ON mins(a_artifact_id, b_artifact_id);

CREATE TABLE artifact_pair_sums(
    a_artifact_id INTEGER NOT NULL REFERENCES artifacts(id),
    b_artifact_id INTEGER NOT NULL REFERENCES artifacts(id),
    cnt INTEGER NOT NULL,
    PRIMARY KEY (a_artifact_id, b_artifact_id)
);

INSERT INTO artifact_pair_sums(a_artifact_id, b_artifact_id, cnt)
SELECT
    mins.a_artifact_id,
    mins.b_artifact_id,
    sum(cnt) AS cnt
FROM
    mins
GROUP BY
    mins.a_artifact_id,
    mins.b_artifact_id;

DROP TABLE mins;

CREATE INDEX idx_artifact_pair_sums_a_artifact_id ON artifact_pair_sums(a_artifact_id);

CREATE INDEX idx_artifact_pair_sums_b_artifact_id ON artifact_pair_sums(b_artifact_id);

CREATE TABLE sums(
    artifact_id INTEGER NOT NULL PRIMARY KEY REFERENCES artifacts(id),
    cnt INTEGER NOT NULL
);

INSERT INTO sums(artifact_id, cnt)
SELECT
    to_artifact_id AS artifact_id,
    sum(cnt) AS cnt
FROM
    artifact_dependency_counts
GROUP BY
    to_artifact_id;

ALTER TABLE artifact_overlap_coefficients RENAME TO osgi_artifact_overlap_coefficients;

CREATE TABLE artifact_overlap_coefficients(
    a_artifact_id INTEGER NOT NULL REFERENCES artifacts(id),
    b_artifact_id INTEGER NOT NULL REFERENCES artifacts(id),
    coeff REAL NOT NULL,
    PRIMARY KEY (a_artifact_id, b_artifact_id)
);

INSERT INTO artifact_overlap_coefficients(a_artifact_id, b_artifact_id, coeff)
SELECT
    artifact_pair_sums.a_artifact_id,
    artifact_pair_sums.b_artifact_id,
    cast(artifact_pair_sums.cnt AS REAL) / least(sums_a.cnt, sums_b.cnt) AS coeff
FROM
    artifact_pair_sums
    JOIN sums AS sums_a ON sums_a.artifact_id = artifact_pair_sums.a_artifact_id
    JOIN sums AS sums_b ON sums_b.artifact_id = artifact_pair_sums.b_artifact_id
    JOIN artifacts AS a_artifacts ON a_artifacts.id = artifact_pair_sums.a_artifact_id
    JOIN artifacts AS b_artifacts ON b_artifacts.id = artifact_pair_sums.b_artifact_id
WHERE
    a_artifacts.root_group_id = b_artifacts.root_group_id;

DROP TABLE sums;

DROP TABLE artifact_pair_sums;

CREATE INDEX idx__artifact_overlap_coefficients_a_artifact_id ON artifact_overlap_coefficients(a_artifact_id);

CREATE INDEX idx__artifact_overlap_coefficients_b_artifact_id ON artifact_overlap_coefficients(b_artifact_id);

INSERT INTO artifact_dependency_counts(from_artifact_id, to_artifact_id, cnt)
SELECT
    artifacts.id AS from_artifact_id,
    artifacts.id AS to_artifact_id,
    count(*) AS cnt
FROM
    versions
    JOIN artifacts ON artifacts.id = versions.artifact_id
GROUP BY
    artifacts.id
ON CONFLICT
    DO NOTHING;

CREATE TABLE mins(
    a_artifact_id INTEGER NOT NULL REFERENCES artifacts(id),
    b_artifact_id INTEGER NOT NULL REFERENCES artifacts(id),
    cnt INTEGER NOT NULL
);

INSERT INTO mins(a_artifact_id, b_artifact_id, cnt)
SELECT
    a.to_artifact_id AS a_artifact_id,
    b.to_artifact_id AS b_artifact_id,
    least(min(a.cnt), min(b.cnt)) AS cnt
FROM
    artifact_dependency_counts AS a
    JOIN artifact_dependency_counts AS b ON b.from_artifact_id = a.from_artifact_id
WHERE
    a.to_artifact_id <= b.to_artifact_id
GROUP BY
    a.from_artifact_id,
    a.to_artifact_id,
    b.to_artifact_id;

CREATE INDEX idx_mins_a_b ON mins(a_artifact_id, b_artifact_id);

CREATE TABLE artifact_pair_sums(
    a_artifact_id INTEGER NOT NULL REFERENCES artifacts(id),
    b_artifact_id INTEGER NOT NULL REFERENCES artifacts(id),
    cnt INTEGER NOT NULL,
    PRIMARY KEY (a_artifact_id, b_artifact_id)
);

INSERT INTO artifact_pair_sums(a_artifact_id, b_artifact_id, cnt)
SELECT
    mins.a_artifact_id,
    mins.b_artifact_id,
    sum(cnt) AS cnt
FROM
    mins
GROUP BY
    mins.a_artifact_id,
    mins.b_artifact_id;

DROP TABLE mins;

CREATE INDEX idx_artifact_pair_sums_a_artifact_id ON artifact_pair_sums(a_artifact_id);

CREATE INDEX idx_artifact_pair_sums_b_artifact_id ON artifact_pair_sums(b_artifact_id);

CREATE TABLE sums(
    artifact_id INTEGER NOT NULL PRIMARY KEY REFERENCES artifacts(id),
    cnt INTEGER NOT NULL
);

INSERT INTO sums(artifact_id, cnt)
SELECT
    to_artifact_id AS artifact_id,
    sum(cnt) AS cnt
FROM
    artifact_dependency_counts
GROUP BY
    to_artifact_id;

CREATE TABLE self_artifact_overlap_coefficients(
    a_artifact_id INTEGER NOT NULL REFERENCES artifacts(id),
    b_artifact_id INTEGER NOT NULL REFERENCES artifacts(id),
    coeff REAL NOT NULL,
    PRIMARY KEY (a_artifact_id, b_artifact_id)
);

INSERT INTO self_artifact_overlap_coefficients(a_artifact_id, b_artifact_id, coeff)
SELECT
    artifact_pair_sums.a_artifact_id,
    artifact_pair_sums.b_artifact_id,
    cast(artifact_pair_sums.cnt AS REAL) / least(sums_a.cnt, sums_b.cnt) AS coeff
FROM
    artifact_pair_sums
    JOIN sums AS sums_a ON sums_a.artifact_id = artifact_pair_sums.a_artifact_id
    JOIN sums AS sums_b ON sums_b.artifact_id = artifact_pair_sums.b_artifact_id
    JOIN artifacts AS a_artifacts ON a_artifacts.id = artifact_pair_sums.a_artifact_id
    JOIN artifacts AS b_artifacts ON b_artifacts.id = artifact_pair_sums.b_artifact_id
WHERE
    a_artifacts.root_group_id = b_artifacts.root_group_id
    AND artifact_pair_sums.a_artifact_id <> artifact_pair_sums.b_artifact_id;

DROP TABLE sums;

DROP TABLE artifact_pair_sums;

CREATE INDEX idx__self_artifact_overlap_coefficients_a_artifact_id ON self_artifact_overlap_coefficients(a_artifact_id);

CREATE INDEX idx__self_artifact_overlap_coefficients_b_artifact_id ON self_artifact_overlap_coefficients(b_artifact_id);

CREATE TABLE communities_parents(
    artifact_id INTEGER NOT NULL PRIMARY KEY REFERENCES artifacts(id),
    community INTEGER NOT NULL
);
