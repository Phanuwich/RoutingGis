package com.gis.myroutingkotlin

import android.util.Log.d
import com.esri.arcgisruntime.geometry.*
import com.esri.arcgisruntime.location.LocationDataSource
import java.util.*

class SimulatedLocationDataSource(routeGeometry: Polyline) : LocationDataSource() {

    private var mCurrentLocation: Point? = null
    private var mRoute: Polyline? = routeGeometry

    private var mTimer: Timer? = null

    private var distance = 0.0
    private val distanceInterval = .00025

    public override fun onStop() {
        mTimer!!.cancel()
    }

    override fun onStart() { // start at the beginning of the route
        d("chikk", "routeeeeeee = $mRoute")
        mCurrentLocation = mRoute!!.parts[0].startPoint
        updateLocation(Location(mCurrentLocation))
        mTimer = Timer("SimulatedLocationDataSource Timer", false)
        mTimer!!.scheduleAtFixedRate(object : TimerTask() {
            override fun run() { // get a reference to the previous point
                val previousPoint = mCurrentLocation
                // update current location by moving [distanceInterval] meters along the route
                mCurrentLocation = GeometryEngine.createPointAlong(mRoute, distance)

                // get the geodetic distance result between the two points
                val distanceResult = GeometryEngine.distanceGeodetic(
                    previousPoint,
                    mCurrentLocation,
                    LinearUnit(LinearUnitId.METERS),
                    AngularUnit(AngularUnitId.DEGREES),
                    GeodeticCurveType.GEODESIC
                )
                // update the location with the current location and use the geodetic distance result to get the azimuth
                updateLocation(
                    Location(
                        mCurrentLocation,
                        1.0,
                        1.0,
                        distanceResult.azimuth1,
                        false
                    )
                )
                // increment the distance
                distance += distanceInterval
//                d("chikk", "distance = $distance")
            }
        }, 0, 1000)
        // this method must be called by the subclass once the location data source has finished its starting process
        onStartCompleted(null)
    }
}