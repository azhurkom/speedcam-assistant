"""
Database initialization script for SpeedCam Assistant.
Creates the cameras table and the insert_or_update_camera function
to prevent duplicate cameras within 50 meters.
"""

import os
import logging

import psycopg2
from psycopg2.extras import RealDictCursor

logger = logging.getLogger(__name__)

DATABASE_URL = os.getenv(
    "DATABASE_URL",
    "postgresql://speedcam:SpeedCam2024!@n8n-db-1:5432/speed_cameras",
)


def get_connection():
    """Create and return a new database connection."""
    return psycopg2.connect(DATABASE_URL, cursor_factory=RealDictCursor)


def init_db():
    """Initialize the database: create tables and the upsert function."""
    conn = None
    try:
        conn = get_connection()
        cur = conn.cursor()

        # Create the cameras table
        cur.execute("""
            CREATE TABLE IF NOT EXISTS cameras (
                id SERIAL PRIMARY KEY,
                lat DOUBLE PRECISION NOT NULL,
                lng DOUBLE PRECISION NOT NULL,
                speed_limit INTEGER NOT NULL DEFAULT 50,
                description VARCHAR(255),
                source VARCHAR(50) DEFAULT 'manual',
                created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
            );
        """)

        # Create the spatial index
        cur.execute("""
            CREATE INDEX IF NOT EXISTS idx_cameras_coords ON cameras (lat, lng);
        """)

        # Create the insert_or_update_camera function
        # This checks whether a camera already exists within ~50 meters
        # (using the Haversine approximation) and avoids duplicates.
        cur.execute("""
            CREATE OR REPLACE FUNCTION insert_or_update_camera(
                p_lat DOUBLE PRECISION,
                p_lng DOUBLE PRECISION,
                p_speed_limit INTEGER DEFAULT 50,
                p_description VARCHAR DEFAULT NULL,
                p_source VARCHAR DEFAULT 'manual'
            ) RETURNS INTEGER AS $$
            DECLARE
                existing_id INTEGER;
                radius_degrees DOUBLE PRECISION := 0.00045;  -- ~50 meters in degrees
            BEGIN
                -- Look for an existing camera within ~50 meters
                SELECT id INTO existing_id
                FROM cameras
                WHERE ABS(lat - p_lat) < radius_degrees
                  AND ABS(lng - p_lng) < radius_degrees
                  AND (lat - p_lat) * (lat - p_lat) + (lng - p_lng) * (lng - p_lng) < radius_degrees * radius_degrees
                LIMIT 1;

                IF existing_id IS NOT NULL THEN
                    -- Update the existing camera
                    UPDATE cameras
                    SET speed_limit = p_speed_limit,
                        description = COALESCE(p_description, description),
                        source = p_source,
                        updated_at = NOW()
                    WHERE id = existing_id;
                    RETURN existing_id;
                ELSE
                    -- Insert a new camera
                    INSERT INTO cameras (lat, lng, speed_limit, description, source)
                    VALUES (p_lat, p_lng, p_speed_limit, p_description, p_source)
                    RETURNING id INTO existing_id;
                    RETURN existing_id;
                END IF;
            END;
            $$ LANGUAGE plpgsql;
        """)

        conn.commit()
        cur.close()
        logger.info("Database initialized successfully: tables and function created.")
        return True

    except Exception as e:
        logger.error(f"Failed to initialize database: {e}")
        if conn:
            conn.rollback()
        return False

    finally:
        if conn:
            conn.close()


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    init_db()