-- Application-form rules: "at least 18?" is answered from the date of birth; "authorized to work?" / "need sponsorship?" from this flag.
ALTER TABLE profiles ADD COLUMN date_of_birth date;
ALTER TABLE profiles ADD COLUMN authorized_to_work boolean NOT NULL DEFAULT true;
