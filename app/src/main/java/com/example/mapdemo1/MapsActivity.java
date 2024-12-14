package com.example.mapdemo1;

import android.Manifest;
import android.content.pm.PackageManager;
import android.animation.ObjectAnimator;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.fragment.app.FragmentActivity;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.SupportStreetViewPanoramaFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.example.mapdemo1.databinding.ActivityMapsBinding;
import com.google.android.libraries.places.api.Places;
import com.google.android.libraries.places.api.model.AutocompletePrediction;
import com.google.android.libraries.places.api.model.AutocompleteSessionToken;
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest;
import com.google.android.libraries.places.api.net.PlacesClient;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

public class MapsActivity extends FragmentActivity implements OnMapReadyCallback {

    private GoogleMap mMap;
    private ActivityMapsBinding binding;
    private SupportStreetViewPanoramaFragment streetViewPanoramaFragment;
    private FusedLocationProviderClient fusedLocationProviderClient;
    private Geocoder geocoder;
    private PlacesClient placesClient;
    private AutoCompleteTextView etSearch;
    private LatLng currentPinnedLocation;
    private Marker currentMarker;
    private ImageView customCompassIcon; // Custom compass icon

    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1;

    private SensorManager sensorManager;
    private Sensor accelerometer;
    private Sensor magnetometer;
    private float[] gravity;
    private float[] geomagnetic;
    private float azimuth;
    private float lastAzimuth;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMapsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Initialize the FusedLocationProviderClient for location services
        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this);

        // Initialize the Places SDK
        Places.initialize(getApplicationContext(), getString(R.string.google_maps_key));
        placesClient = Places.createClient(this);

        // Initialize Geocoder for geocoding and reverse geocoding
        geocoder = new Geocoder(this, Locale.getDefault());

        // Obtain the SupportMapFragment and get notified when the map is ready to be used.
        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map);
        mapFragment.getMapAsync(this);

        // Initialize the Street View fragment
        streetViewPanoramaFragment = (SupportStreetViewPanoramaFragment) getSupportFragmentManager()
                .findFragmentById(R.id.street_view_fragment);

        // Set up buttons for changing views
        Button btnNormalView = findViewById(R.id.btnNormalView);
        Button btnSatelliteView = findViewById(R.id.btnSatelliteView);
        Button btnStreetView = findViewById(R.id.btnStreetView);
        Button btnCurrentLocation = findViewById(R.id.btnCurrentLocation);

        // Initialize the custom compass icon
        customCompassIcon = findViewById(R.id.customCompassIcon);

        // Find search bar and button
        etSearch = findViewById(R.id.etSearch);
        Button btnSearch = findViewById(R.id.btnSearch);

        // Set a listener for the search button (Geocoding)
        btnSearch.setOnClickListener(v -> {
            String location = etSearch.getText().toString();
            if (!location.isEmpty()) {
                searchLocation(location);
            } else {
                Toast.makeText(MapsActivity.this, "Please enter a location", Toast.LENGTH_SHORT).show();
            }
        });

        // Add TextChangeListener to AutoCompleteTextView for suggestions
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                getSuggestions(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        // Set listeners for map type switching
        btnNormalView.setOnClickListener(v -> {
            if (mMap != null) {
                mMap.setMapType(GoogleMap.MAP_TYPE_NORMAL);
                hideStreetView();
            }
        });

        btnSatelliteView.setOnClickListener(v -> {
            if (mMap != null) {
                mMap.setMapType(GoogleMap.MAP_TYPE_SATELLITE);
                hideStreetView();
            }
        });

        btnStreetView.setOnClickListener(v -> {
            if (currentPinnedLocation != null) {
                showStreetView(currentPinnedLocation);
            } else {
                Toast.makeText(MapsActivity.this, "Please place a marker first", Toast.LENGTH_SHORT).show();
            }
        });

        // Show current location when the button is clicked
        btnCurrentLocation.setOnClickListener(v -> getCurrentLocation());

        // Initialize sensors for compass
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);

        // Register sensor listeners
        sensorManager.registerListener(sensorEventListener, accelerometer, SensorManager.SENSOR_DELAY_UI);
        sensorManager.registerListener(sensorEventListener, magnetometer, SensorManager.SENSOR_DELAY_UI);
    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
        mMap = googleMap;

        // Add a listener to place or move a marker on map click
        mMap.setOnMapClickListener(latLng -> {
            if (currentMarker != null) {
                currentMarker.setPosition(latLng);
            } else {
                currentMarker = mMap.addMarker(new MarkerOptions().position(latLng).title("Pinned Location"));
            }
            currentPinnedLocation = latLng;
            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15));
        });

        // Enable the My Location layer if permissions are granted
        enableMyLocation();
    }

    private void enableMyLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                    LOCATION_PERMISSION_REQUEST_CODE);
        } else {
            if (mMap != null) {
                mMap.setMyLocationEnabled(true);
            }
        }
    }

    private void searchLocation(String locationName) {
        try {
            List<Address> addressList = geocoder.getFromLocationName(locationName, 1);
            if (addressList != null && !addressList.isEmpty()) {
                Address address = addressList.get(0);
                LatLng latLng = new LatLng(address.getLatitude(), address.getLongitude());
                if (currentMarker != null) {
                    currentMarker.setPosition(latLng);
                } else {
                    currentMarker = mMap.addMarker(new MarkerOptions().position(latLng).title(address.getAddressLine(0)));
                }
                currentPinnedLocation = latLng;
                mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15));
            } else {
                Toast.makeText(this, "Location not found", Toast.LENGTH_SHORT).show();
            }
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(this, "Unable to find the location", Toast.LENGTH_SHORT).show();
        }
    }

    private void getSuggestions(String query) {
        AutocompleteSessionToken token = AutocompleteSessionToken.newInstance();
        FindAutocompletePredictionsRequest request = FindAutocompletePredictionsRequest.builder()
                .setSessionToken(token)
                .setQuery(query)
                .build();

        placesClient.findAutocompletePredictions(request).addOnSuccessListener(response -> {
            String[] suggestions = new String[response.getAutocompletePredictions().size()];
            for (int i = 0; i < response.getAutocompletePredictions().size(); i++) {
                AutocompletePrediction prediction = response.getAutocompletePredictions().get(i);
                suggestions[i] = prediction.getFullText(null).toString();
            }
            android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(this,
                    android.R.layout.simple_dropdown_item_1line, suggestions);
            etSearch.setAdapter(adapter);
            etSearch.showDropDown();
        }).addOnFailureListener(e -> Toast.makeText(this, "Error fetching suggestions: " + e.getMessage(), Toast.LENGTH_SHORT).show());
    }

    private void getCurrentLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                    LOCATION_PERMISSION_REQUEST_CODE);
            return;
        }

        Task<Location> locationTask = fusedLocationProviderClient.getLastLocation();
        locationTask.addOnSuccessListener(location -> {
            if (location != null) {
                LatLng currentLocation = new LatLng(location.getLatitude(), location.getLongitude());
                mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(currentLocation, 15.0f));
                if (currentMarker != null) {
                    currentMarker.setPosition(currentLocation);
                } else {
                    currentMarker = mMap.addMarker(new MarkerOptions().position(currentLocation).title("You are here"));
                }
                currentPinnedLocation = currentLocation;
            }
        });
    }

    private void showStreetView(LatLng location) {
        findViewById(R.id.map).setVisibility(View.GONE);
        findViewById(R.id.street_view_fragment).setVisibility(View.VISIBLE);
        streetViewPanoramaFragment.getStreetViewPanoramaAsync(panorama -> {
            panorama.setPosition(location);
            panorama.setStreetNamesEnabled(true);
            panorama.setUserNavigationEnabled(true);
            panorama.setZoomGesturesEnabled(true);
        });
    }

    private void hideStreetView() {
        findViewById(R.id.street_view_fragment).setVisibility(View.GONE);
        findViewById(R.id.map).setVisibility(View.VISIBLE);
    }

    private final SensorEventListener sensorEventListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                gravity = event.values.clone();
            } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
                geomagnetic = event.values.clone();
            }

            if (gravity != null && geomagnetic != null) {
                float[] R = new float[9];
                float[] I = new float[9];
                if (SensorManager.getRotationMatrix(R, I, gravity, geomagnetic)) {
                    float[] orientation = new float[3];
                    SensorManager.getOrientation(R, orientation);
                    azimuth = (float) Math.toDegrees(orientation[0]);
                    azimuth = (azimuth + 360) % 360;

                    // Apply low-pass filter for smooth rotation
                    azimuth = 0.8f * lastAzimuth + 0.2f * azimuth;
                    lastAzimuth = azimuth;

                    animateCompassRotation(azimuth);
                }
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };

    private void animateCompassRotation(float newAzimuth) {
        if (customCompassIcon != null) {
            ObjectAnimator rotationAnimator = ObjectAnimator.ofFloat(customCompassIcon, "rotation", lastAzimuth, newAzimuth);
            rotationAnimator.setDuration(250); // Adjust duration for smoothness
            rotationAnimator.start();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(sensorEventListener);
    }

    @Override
    protected void onResume() {
        super.onResume();
        sensorManager.registerListener(sensorEventListener, accelerometer, SensorManager.SENSOR_DELAY_UI);
        sensorManager.registerListener(sensorEventListener, magnetometer, SensorManager.SENSOR_DELAY_UI);
    }
}
