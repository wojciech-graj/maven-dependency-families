ALTER TABLE bundle_versions
    DROP COLUMN export_package,
    DROP COLUMN import_package,
    DROP COLUMN require_bundle,
    DROP COLUMN export_service;

VACUUM
    FULL;
