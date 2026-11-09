-- How many deletions the user accepted from a sweep the mass-delete guard held back. A later sweep
-- may delete that many at most, and applying a sweep clears it.
ALTER TABLE harvest_sources ADD COLUMN accepted_sweep_count INTEGER;
