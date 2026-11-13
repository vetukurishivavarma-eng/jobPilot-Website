-- UPI payments start at once (trust first); an admin marks each one received (checked_at) or revokes it.
ALTER TABLE purchases ADD COLUMN checked_at TIMESTAMP WITH TIME ZONE;
