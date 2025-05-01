ALTER TABLE bundle_versions
    DROP COLUMN export_package,
    DROP COLUMN import_package,
    DROP COLUMN require_bundle,
    DROP COLUMN export_service;

CREATE INDEX idx_exported_packages_package_id ON exported_packages(package_id);

CREATE INDEX idx_impored_packages_package_id ON imported_packages(to_package_id);

CREATE TABLE versions_in_range(
    version_range TEXT NOT NULL,
    version TEXT NOT NULL
);
