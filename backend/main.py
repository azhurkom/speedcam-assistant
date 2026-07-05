"""
SpeedCam Assistant API — FastAPI backend.

Provides CRUD endpoints for speed cameras with proximity-based
duplicate detection and spatial queries.
"""

import logging
import os

import psycopg2
from psycopg2.extras import RealDictCursor
from fastapi import FastAPI, HTTPException, Query, Body
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field
from typing import Optional

from init_db import init_db, get_connection

# ── Logging ──────────────────────────────────────────────────────────────
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger("speedcam-api")

# ── App ──────────────────────────────────────────────────────────────────
app = FastAPI(title="SpeedCam Assistant API", version="1.0.0")

# CORS — allow all origins
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# ── Pydantic models ──────────────────────────────────────────────────────


class CameraCreate(BaseModel):
    lat: float = Field(..., description="Latitude")
    lng: float = Field(..., description="Longitude")
    speed_limit: int = Field(50, description="Speed limit in km/h")
    description: Optional[str] = Field(None, description="Optional description")


class CameraNearbyDelete(BaseModel):
    lat: float = Field(..., description="Latitude")
    lng: float = Field(..., description="Longitude")


class CameraOut(BaseModel):
    id: int
    lat: float
    lng: float
    speed_limit: int
    description: Optional[str] = None
    source: str = "manual"
    created_at: Optional[str] = None
    updated_at: Optional[str] = None


# ── Startup event ────────────────────────────────────────────────────────


@app.on_event("startup")
def on_startup():
    """Initialize the database on application startup."""
    logger.info("Running database initialization…")
    success = init_db()
    if success:
        logger.info("Database initialization completed successfully.")
    else:
        logger.warning("Database initialization encountered issues — check logs above.")


# ── Helper ───────────────────────────────────────────────────────────────


def get_db():
    """Return a new database connection (context-manager style)."""
    conn = get_connection()
    try:
        yield conn
    finally:
        conn.close()


def degrees_for_meters(meters: float) -> float:
    """
    Approximate conversion from meters to degrees at mid-latitudes.
    ~111 000 m per degree ≈ 0.000009 °/m → 0.009 °/km
    """
    return meters / 111_000.0


# ── Endpoints ────────────────────────────────────────────────────────────


@app.get("/health")
def health_check():
    """Health check endpoint."""
    return {"status": "ok"}


@app.get("/api/cameras", response_model=list[CameraOut])
def get_cameras(
    lat: Optional[float] = Query(None, description="Latitude for proximity filter"),
    lng: Optional[float] = Query(None, description="Longitude for proximity filter"),
):
    """
    Return all cameras. If lat & lng are provided, filter to a 10 km radius.
    """
    conn = None
    try:
        conn = get_connection()
        cur = conn.cursor()

        if lat is not None and lng is not None:
            radius_deg = degrees_for_meters(10_000.0)  # 10 km
            cur.execute(
                """
                SELECT id, lat, lng, speed_limit, description, source,
                       created_at::TEXT, updated_at::TEXT
                FROM cameras
                WHERE ABS(lat - %s) < %s
                  AND ABS(lng - %s) < %s
                  AND (lat - %s) * (lat - %s) + (lng - %s) * (lng - %s) < %s * %s
                ORDER BY (lat - %s) * (lat - %s) + (lng - %s) * (lng - %s)
                """,
                (lat, radius_deg, lng, radius_deg,
                 lat, lat, lng, lng, radius_deg, radius_deg,
                 lat, lat, lng, lng),
            )
        else:
            cur.execute(
                """
                SELECT id, lat, lng, speed_limit, description, source,
                       created_at::TEXT, updated_at::TEXT
                FROM cameras
                ORDER BY id
                """
            )

        rows = cur.fetchall()
        cur.close()
        return [dict(r) for r in rows]

    except Exception as e:
        logger.error(f"GET /api/cameras failed: {e}")
        raise HTTPException(status_code=500, detail=str(e))
    finally:
        if conn:
            conn.close()


@app.get("/api/cameras/nearby", response_model=list[CameraOut])
def get_cameras_nearby(
    lat: float = Query(..., description="Latitude"),
    lng: float = Query(..., description="Longitude"),
    radius: float = Query(1000.0, description="Search radius in meters (default 1000)"),
):
    """
    Return cameras within a given radius (meters) from the supplied coordinates.
    Used to check if a camera is nearby (for alerts).
    """
    conn = None
    try:
        conn = get_connection()
        cur = conn.cursor()
        radius_deg = degrees_for_meters(radius)

        cur.execute(
            """
            SELECT id, lat, lng, speed_limit, description, source,
                   created_at::TEXT, updated_at::TEXT
            FROM cameras
            WHERE ABS(lat - %s) < %s
              AND ABS(lng - %s) < %s
              AND (lat - %s) * (lat - %s) + (lng - %s) * (lng - %s) < %s * %s
            ORDER BY (lat - %s) * (lat - %s) + (lng - %s) * (lng - %s)
            """,
            (lat, radius_deg, lng, radius_deg,
             lat, lat, lng, lng, radius_deg, radius_deg,
             lat, lat, lng, lng),
        )

        rows = cur.fetchall()
        cur.close()
        return [dict(r) for r in rows]

    except Exception as e:
        logger.error(f"GET /api/cameras/nearby failed: {e}")
        raise HTTPException(status_code=500, detail=str(e))
    finally:
        if conn:
            conn.close()


@app.post("/api/cameras", response_model=CameraOut, status_code=201)
def create_camera(camera: CameraCreate):
    """
    Add a new camera. Uses the insert_or_update_camera database function
    to avoid duplicates within ~50 meters.
    """
    conn = None
    try:
        conn = get_connection()
        cur = conn.cursor()

        cur.execute(
            "SELECT insert_or_update_camera(%s, %s, %s, %s, 'manual') AS cam_id",
            (camera.lat, camera.lng, camera.speed_limit, camera.description),
        )
        row = cur.fetchone()
        cam_id = row["cam_id"]
        conn.commit()

        # Fetch the full record
        cur.execute(
            """
            SELECT id, lat, lng, speed_limit, description, source,
                   created_at::TEXT, updated_at::TEXT
            FROM cameras
            WHERE id = %s
            """,
            (cam_id,),
        )
        result = dict(cur.fetchone())
        cur.close()
        return result

    except Exception as e:
        logger.error(f"POST /api/cameras failed: {e}")
        if conn:
            conn.rollback()
        raise HTTPException(status_code=500, detail=str(e))
    finally:
        if conn:
            conn.close()


@app.delete("/api/cameras/{camera_id}", status_code=200)
def delete_camera_by_id(camera_id: int):
    """
    Delete a camera by its ID.
    """
    conn = None
    try:
        conn = get_connection()
        cur = conn.cursor()

        cur.execute("DELETE FROM cameras WHERE id = %s RETURNING id", (camera_id,))
        deleted = cur.fetchone()
        conn.commit()
        cur.close()

        if deleted is None:
            raise HTTPException(status_code=404, detail="Camera not found")

        return {"deleted_id": camera_id}

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"DELETE /api/cameras/{camera_id} failed: {e}")
        if conn:
            conn.rollback()
        raise HTTPException(status_code=500, detail=str(e))
    finally:
        if conn:
            conn.close()


@app.delete("/api/cameras/nearby", status_code=200)
def delete_camera_nearby(body: CameraNearbyDelete = Body(...)):
    """
    Delete the nearest camera within 200 meters of the given coordinates.
    """
    conn = None
    try:
        conn = get_connection()
        cur = conn.cursor()
        radius_deg = degrees_for_meters(200.0)

        # Find the closest camera within 200 m
        cur.execute(
            """
            SELECT id,
                   (lat - %s) * (lat - %s) + (lng - %s) * (lng - %s) AS dist_sq
            FROM cameras
            WHERE ABS(lat - %s) < %s
              AND ABS(lng - %s) < %s
              AND (lat - %s) * (lat - %s) + (lng - %s) * (lng - %s) < %s * %s
            ORDER BY dist_sq
            LIMIT 1
            """,
            (body.lat, body.lat, body.lng, body.lng,
             body.lat, radius_deg, body.lng, radius_deg,
             body.lat, body.lat, body.lng, body.lng, radius_deg, radius_deg),
        )

        row = cur.fetchone()
        if row is None:
            raise HTTPException(
                status_code=404,
                detail="No camera found within 200 meters of the supplied coordinates",
            )

        cam_id = row["id"]
        cur.execute("DELETE FROM cameras WHERE id = %s", (cam_id,))
        conn.commit()
        cur.close()

        return {"deleted_id": cam_id}

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"DELETE /api/cameras/nearby failed: {e}")
        if conn:
            conn.rollback()
        raise HTTPException(status_code=500, detail=str(e))
    finally:
        if conn:
            conn.close()