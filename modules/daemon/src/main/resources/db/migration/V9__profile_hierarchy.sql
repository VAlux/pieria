-- Profiles nest like folders: a profile may have one parent, and a recall on a profile also reads
-- every profile below it. Names stay flat and unique; the tree lives only in this column.
ALTER TABLE profiles ADD COLUMN parent_id TEXT REFERENCES profiles(id);
CREATE INDEX idx_profiles_parent ON profiles(parent_id);
