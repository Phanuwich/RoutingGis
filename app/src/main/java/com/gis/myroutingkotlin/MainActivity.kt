package com.gis.myroutingkotlin

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Color
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeech.OnInitListener
import android.text.format.DateUtils
import android.util.Log
import android.util.Log.d
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.esri.arcgisruntime.concurrent.ListenableFuture
import com.esri.arcgisruntime.geometry.Point
import com.esri.arcgisruntime.geometry.SpatialReferences
import com.esri.arcgisruntime.mapping.ArcGISMap
import com.esri.arcgisruntime.mapping.Basemap
import com.esri.arcgisruntime.mapping.Viewpoint
import com.esri.arcgisruntime.mapping.view.Graphic
import com.esri.arcgisruntime.mapping.view.GraphicsOverlay
import com.esri.arcgisruntime.mapping.view.LocationDisplay
import com.esri.arcgisruntime.navigation.DestinationStatus
import com.esri.arcgisruntime.navigation.RouteTracker
import com.esri.arcgisruntime.navigation.RouteTracker.NewVoiceGuidanceEvent
import com.esri.arcgisruntime.navigation.RouteTracker.NewVoiceGuidanceListener
import com.esri.arcgisruntime.navigation.TrackingStatus
import com.esri.arcgisruntime.symbology.SimpleLineSymbol
import com.esri.arcgisruntime.tasks.networkanalysis.RouteParameters
import com.esri.arcgisruntime.tasks.networkanalysis.RouteResult
import com.esri.arcgisruntime.tasks.networkanalysis.RouteTask
import com.esri.arcgisruntime.tasks.networkanalysis.Stop
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.karumi.dexter.Dexter
import com.karumi.dexter.MultiplePermissionsReport
import com.karumi.dexter.PermissionToken
import com.karumi.dexter.listener.PermissionRequest
import com.karumi.dexter.listener.multi.MultiplePermissionsListener
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.layout_navigation_controls.*
import java.util.*
import java.util.concurrent.ExecutionException

class MainActivity : AppCompatActivity() {

    private var mTextToSpeech: TextToSpeech? = null
    private var mIsTextToSpeechInitialized = false

    private var mRouteAheadGraphic: Graphic? = null
    private var mRouteTraveledGraphic: Graphic? = null
    private var mSimulatedLocationDataSource: SimulatedLocationDataSource? = null

    private var mRouteTracker: RouteTracker? = null

    private lateinit var mLocationDisplay: LocationDisplay

    lateinit var location: Location
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // create a map and set it to the map view
        val map = ArcGISMap(Basemap.createStreetsVector())
        mapView.map = map

//        setupLocationDisplay()
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        Dexter.withActivity(this)
            .withPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)
            .withListener(object : MultiplePermissionsListener {
                @SuppressLint("MissingPermission")
                override fun onPermissionsChecked(report: MultiplePermissionsReport) {
                    if (report.areAllPermissionsGranted()) {
                        fusedLocationClient?.requestLocationUpdates(LocationRequest(), object : LocationCallback() {
                            override fun onLocationResult(loc: LocationResult) {
                                location = loc.lastLocation
                                location?.let {
                                    val point = Point(it.latitude, it.longitude, SpatialReferences.getWgs84())
                                    d("chikk","pointttt = $point")
                                }
                            }
                        }, null)

                    }
                }

                override fun onPermissionRationaleShouldBeShown(permissions: MutableList<PermissionRequest>?, token: PermissionToken?) {
                    token?.continuePermissionRequest()
                }

            })
            .check()





        // create a graphics overlay to hold our route graphics
        val graphicsOverlay = GraphicsOverlay()
        mapView.graphicsOverlays.add(graphicsOverlay)

        // initialize text-to-speech to replay navigation voice guidance
        mTextToSpeech = TextToSpeech(this, OnInitListener { status: Int ->
            if (status != TextToSpeech.ERROR) {
                mTextToSpeech!!.language = Resources.getSystem().configuration.locale
                mIsTextToSpeechInitialized = true
            }
        })

        // clear any graphics from the current graphics overlay
        mapView.graphicsOverlays[0].graphics.clear()

        // generate a route with directions and stops for navigation
        val routeTask = RouteTask(this, getString(R.string.routing_service_url2))
        val routeParametersFuture = routeTask.createDefaultParametersAsync()

        routeParametersFuture.addDoneListener {
            try { // define the route parameters
                val routeParameters = routeParametersFuture.get()
                routeParameters.setStops(getStops())
                routeParameters.isReturnDirections = true
                routeParameters.isReturnStops = true
                routeParameters.isReturnRoutes = true
                val routeResultFuture = routeTask.solveRouteAsync(routeParameters)
                routeParametersFuture.addDoneListener {
                    try { // get the route geometry from the route result
                        val routeResult = routeResultFuture.get()
                        val routeGeometry = routeResult.routes[0].routeGeometry
                        // create a graphic for the route geometry
                        val routeGraphic = Graphic(routeGeometry,
                                SimpleLineSymbol(SimpleLineSymbol.Style.SOLID, Color.BLUE, 5f))
                        // add it to the graphics overlay
                        mapView.graphicsOverlays[0].graphics.add(routeGraphic)
                        // set the map view view point to show the whole route
                        mapView.setViewpointAsync(Viewpoint(routeGeometry.extent))
                        // create a button to start navigation with the given route
                        val navigateRouteButton = findViewById<Button>(R.id.navigateRouteButton)
                        navigateRouteButton.setOnClickListener { v: View? -> startNavigation(routeTask, routeParameters, routeResult) }
                        // start navigating
                        startNavigation(routeTask, routeParameters, routeResult)
                    } catch (e: ExecutionException) {
                        val error = "Error creating default route parameters: " + e.message
                        Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                        Log.e("chikk", error)
                    } catch (e: InterruptedException) {
                        val error = "Error creating default route parameters: " + e.message
                        Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                        Log.e("chikk", error)
                    }
                }
            } catch (e: InterruptedException) {
                val error = "Error getting the route result " + e.message
                Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                Log.e("chikk", error)
            } catch (e: ExecutionException) {
                val error = "Error getting the route result " + e.message
                Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                Log.e("chikk", error)
            }
        }

        recenterButton.isEnabled = false
        recenterButton.setOnClickListener(View.OnClickListener { v: View? ->
            mapView.locationDisplay.autoPanMode = LocationDisplay.AutoPanMode.NAVIGATION
            recenterButton.isEnabled = false
        })
    }

    fun setupLocationDisplay() {
        mapView.locationDisplay.isShowLocation = true
        mLocationDisplay = mapView.locationDisplay
        mLocationDisplay.addDataSourceStatusChangedListener {
            if (it.isStarted || it.error == null){

                d("chikk","currentttttttt2222 = ${mLocationDisplay.mapLocation}")
                return@addDataSourceStatusChangedListener
            }
            var requestPermissionsCode = 99
            var requestPermissions = arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)

            if ((ContextCompat.checkSelfPermission(this, requestPermissions[0]) == PackageManager.PERMISSION_GRANTED
                        && ContextCompat.checkSelfPermission(this, requestPermissions[1]) == PackageManager.PERMISSION_GRANTED)){
                ActivityCompat.requestPermissions(this, requestPermissions, requestPermissionsCode)
            }else{
                val message = String.format("Error in DataSourceStatusChangedListener: %s", it.source.locationDataSource.error.message)
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            }
        }

        mLocationDisplay.autoPanMode = LocationDisplay.AutoPanMode.COMPASS_NAVIGATION
        mLocationDisplay.startAsync()

    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            mLocationDisplay.startAsync()

        } else {
            Toast.makeText(this, resources.getString(R.string.location_permission_denied), Toast.LENGTH_SHORT).show()
        }
    }

    private fun getStops(): List<Stop>? {
        d("chikk","pointttt2 = ${location.latitude}")
        val stops: MutableList<Stop> = ArrayList(3)

        val point = Point(location.latitude, location.longitude, SpatialReferences.getWgs84())
        val conventionCenter = Stop(point)
        stops.add(conventionCenter)
        // USS San Diego Memorial
        val memorial = Stop(Point(100.412123, 13.876779, SpatialReferences.getWgs84()))
        stops.add(memorial)
        // RH Fleet Aerospace Museum
        val aerospaceMuseum = Stop(Point(100.443162, 13.877435, SpatialReferences.getWgs84()))
        stops.add(aerospaceMuseum)

        return stops
    }

    private fun startNavigation(routeTask: RouteTask, routeParameters: RouteParameters, routeResult: RouteResult) { // clear any graphics from the current graphics overlay
        mapView.graphicsOverlays[0].graphics.clear()
        // get the route's geometry from the route result
        val routeGeometry = routeResult.routes[0].routeGeometry
        // create a graphic (with a dashed line symbol) to represent the route
        mRouteAheadGraphic = Graphic(routeGeometry,
                SimpleLineSymbol(SimpleLineSymbol.Style.DASH, Color.MAGENTA, 5f))
        mapView.graphicsOverlays[0].graphics.add(mRouteAheadGraphic)
        // create a graphic (solid) to represent the route that's been traveled (initially empty)
        mRouteTraveledGraphic = Graphic(routeGeometry,
                SimpleLineSymbol(SimpleLineSymbol.Style.SOLID, Color.BLUE, 5f))
        mapView.graphicsOverlays[0].graphics.add(mRouteTraveledGraphic)
        // get the map view's location display
        val locationDisplay: LocationDisplay = mapView.locationDisplay
        // set up a simulated location data source which simulates movement along the route
        mSimulatedLocationDataSource = SimulatedLocationDataSource(routeGeometry)
        // set the simulated location data source as the location data source for this app
        locationDisplay.locationDataSource = mSimulatedLocationDataSource
        locationDisplay.autoPanMode = LocationDisplay.AutoPanMode.NAVIGATION
        // if the user navigates the map view away from the location display, activate the recenter button
        locationDisplay.addAutoPanModeChangedListener { recenterButton.isEnabled = true }
        // set up a RouteTracker for navigation along the calculated route
        mRouteTracker = RouteTracker(applicationContext, routeResult, 0)
        mRouteTracker?.enableReroutingAsync(routeTask, routeParameters,
                RouteTracker.ReroutingStrategy.TO_NEXT_WAYPOINT, true)
        // get a reference to navigation text views
        val distanceRemainingTextView = findViewById<TextView>(R.id.distanceRemainingTextView)
        val timeRemainingTextView = findViewById<TextView>(R.id.timeRemainingTextView)
        val nextDirectionTextView = findViewById<TextView>(R.id.nextDirectionTextView)
        // listen for changes in location
        locationDisplay.addLocationChangedListener { locationChangedEvent: LocationDisplay.LocationChangedEvent ->
            // track the location and update route tracking status
            val trackLocationFuture: ListenableFuture<Void>? = mRouteTracker?.trackLocationAsync(locationChangedEvent.location)
            trackLocationFuture?.addDoneListener {
                // listen for new voice guidance events
                mRouteTracker?.addNewVoiceGuidanceListener { newVoiceGuidanceEvent: NewVoiceGuidanceEvent ->
                    // use Android's text to speech to speak the voice guidance
                    speakVoiceGuidance(newVoiceGuidanceEvent.voiceGuidance.text)
                    nextDirectionTextView.text = getString(R.string.next_direction, newVoiceGuidanceEvent.voiceGuidance.text)
                }
                // get the route's tracking status
                val trackingStatus: TrackingStatus? = mRouteTracker?.trackingStatus
                // set geometries for the route ahead and the remaining route
                mRouteAheadGraphic?.geometry = trackingStatus?.routeProgress?.remainingGeometry
                mRouteTraveledGraphic?.geometry = trackingStatus?.routeProgress?.traversedGeometry
                // get remaining distance information
                val remainingDistance = trackingStatus?.destinationProgress?.remainingDistance
                // covert remaining minutes to hours:minutes:seconds
                val remainingTimeString = DateUtils
                        .formatElapsedTime(((trackingStatus?.destinationProgress?.remainingTime)!! * 60).toLong())
                // update text views
                distanceRemainingTextView.text = getString(R.string.distance_remaining, remainingDistance?.displayText,
                        remainingDistance?.displayTextUnits?.pluralDisplayName)
                timeRemainingTextView.text = getString(R.string.time_remaining, remainingTimeString)
                // if a destination has been reached
                if (trackingStatus.destinationStatus == DestinationStatus.REACHED) { // if there are more destinations to visit. Greater than 1 because the start point is considered a "stop"
                    if (mRouteTracker?.trackingStatus?.remainingDestinationCount!! > 1) { // switch to the next destination
                        mRouteTracker!!.switchToNextDestinationAsync()
                        Toast.makeText(this, "Navigating to the second stop, the Fleet Science Center.", Toast.LENGTH_LONG).show()
                    } else { // the final destination has been reached, stop the simulated location data source
                        mSimulatedLocationDataSource!!.onStop()
                        Toast.makeText(this, "Arrived at the final destination.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        // start the LocationDisplay, which starts the SimulatedLocationDataSource
        locationDisplay.startAsync()
        Toast.makeText(this, "Navigating to the first stop, the USS San Diego Memorial.", Toast.LENGTH_LONG).show()
    }


    private fun speakVoiceGuidance(voiceGuidanceText: String) {
        if (mIsTextToSpeechInitialized && !mTextToSpeech!!.isSpeaking) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mTextToSpeech!!.speak(voiceGuidanceText, TextToSpeech.QUEUE_FLUSH, null, null)
            } else {
                mTextToSpeech!!.speak(voiceGuidanceText, TextToSpeech.QUEUE_FLUSH, null)
            }
        }
    }




}
