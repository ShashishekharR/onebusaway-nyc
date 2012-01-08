/**
 * Copyright (c) 2011 Metropolitan Transportation Authority
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.onebusaway.nyc.vehicle_tracking.impl.particlefilter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.onebusaway.nyc.vehicle_tracking.impl.inference.distributions.CategoricalDist;
import org.onebusaway.nyc.vehicle_tracking.impl.inference.state.VehicleState;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Ordering;

/**
 * Core particle filter implementation.<br>
 * Note: This filter has an experimental multi-threading option that is enabled
 * by setting _threads > 1.
 * 
 * @author bwillard, bdferris
 * 
 * @param <OBS>
 */
public class ParticleFilter<OBS> {

  private ParticleFactory<OBS> _particleFactory;

  private SensorModel<OBS> _sensorModel;

  private MotionModel<OBS> _motionModel = null;

  private List<Particle> _particles = new ArrayList<Particle>();

  private List<Particle> _weightedParticles = new ArrayList<Particle>();

  private double _timeOfLastUpdate = 0L;

  private boolean _seenFirst;

  private Particle _mostLikelyParticle;

  private Particle _leastLikelyParticle;

  final private static int _threads = Math.max(
      Runtime.getRuntime().availableProcessors() / 2, 1);

  final private static Boolean _maxLikelihoodParticle = Boolean.FALSE;

  final private static Boolean _debugEnabled = Boolean.FALSE;

  private static final Boolean _moveAndWeight = Boolean.TRUE;

  private ExecutorService _executorService;

  /***************************************************************************
   * Public Methods
   **************************************************************************/

  public ParticleFilter(ParticleFilterModel<OBS> model) {
    this(model.getParticleFactory(), model.getSensorModel(),
        model.getMotionModel());
  }

  @SuppressWarnings("unused")
  public ParticleFilter(ParticleFactory<OBS> particleFactory,
      SensorModel<OBS> sensorModel, MotionModel<OBS> motionModel) {
    if (_threads > 1)
      _executorService = Executors.newFixedThreadPool(_threads);
    _particleFactory = particleFactory;
    _sensorModel = sensorModel;
    _motionModel = motionModel;
  }

  public void reset() {
    if (_particles != null) {
      _particles.clear();
      _particles = null;
    }
    _timeOfLastUpdate = 0L;
    _seenFirst = false;
  }

  public List<Particle> getParticleList() {
    return Collections.unmodifiableList(_particles);
  }

  public List<Particle> getWeightedParticles() {
    return Collections.unmodifiableList(_weightedParticles);
  }

  public List<Particle> getSampledParticles() {
    return Collections.unmodifiableList(_particles);
  }

  public Particle getMostLikelyParticle() {
    return _mostLikelyParticle;
  }

  public Particle getLeastLikelyParticle() {
    return _leastLikelyParticle;
  }

  /**
   * @return the last time at which updateFilter was called, in milliseconds
   */
  public double getTimeOfLastUpdated() {
    return _timeOfLastUpdate;
  }

  /**
   * This is what you can use to initialize the filter if you have a particular
   * set of particles you want to use
   */
  public void initializeFilter(Collection<Particle> initialParticles) {
    if (_seenFirst)
      throw new IllegalStateException(
          "Error: particle filter has already been initialized.");
    _particles = new ArrayList<Particle>(initialParticles);
    _seenFirst = true;
  }

  /**
   * This is the major method that will be called repeatedly by an outside
   * client in order to drive the filter. Each call runs a single timestep.
   */
  public void updateFilter(double timestamp, OBS observation)
      throws ParticleFilterException {

    boolean firstTime = checkFirst(timestamp, observation);
    runSingleTimeStep(timestamp, observation, !firstTime);
    _timeOfLastUpdate = timestamp;
  }

  /***************************************************************************
   * Abstract Methods
   **************************************************************************/

  /*
   * Returns an ArrayList, each entry of which is a Particle. This allows
   * subclasses to determine what kind of particles to create, and where to
   * place them.
   */
  protected List<Particle> createInitialParticlesFromObservation(
      double timestamp, OBS observation) {
    return _particleFactory.createParticles(timestamp, observation);
  }

  /***************************************************************************
   * Private Methods
   **************************************************************************/

  /**
   * @return true if this is the initial entry for these particles
   */
  private boolean checkFirst(double timestamp, OBS observation) {

    if (!_seenFirst) {
      _particles = createInitialParticlesFromObservation(timestamp, observation);
      _seenFirst = true;
      _timeOfLastUpdate = timestamp;
      return true;
    }

    return false;
  }

  /**
   * Simply returns an integer sequence of step size totalTasks/_threads, within
   * [0, totalTasks-1]
   * 
   * @param totalTasks
   * @return
   */
  static private List<Integer[]> getTaskSequence(int totalTasks) {
    int stepSize = Math.max(totalTasks / _threads, 1);
    List<Integer[]> segments = new ArrayList<Integer[]>();
    int runAmt = 0;
    for (int i = 0; i < _threads; ++i) {
      Integer[] interval = new Integer[2];
      interval[0] = runAmt;
      runAmt += stepSize;
      interval[1] = runAmt;
      segments.add(interval);
    }

    /*
     * add remainder of tasks to last group
     */
    if (segments.get(segments.size() - 1)[1] < totalTasks - 1) {
      segments.get(segments.size() - 1)[1] = totalTasks - 1;
    }

    return segments;
  }

  /**
   * This runs a single time-step of the particle filter, given a single
   * timestep's worth of sensor readings. Julie's note to self: see
   * KLDParticleFilter's implementation for hints on what existing functions you
   * can use.
   * 
   * @param firstTime
   */
  private void runSingleTimeStep(double timestamp, OBS obs,
      boolean moveParticles) throws ParticleFilterException {

    int numberOfParticles = _particles.size();
    List<Particle> particles = _particles;

    /**
     * 1. apply the motion model to each particle 2. apply the sensor
     * model(likelihood) to each particle
     */
    CategoricalDist<Particle> cdf;
    if (_moveAndWeight && _threads > 1) {

      if (moveParticles) {
        cdf = new CategoricalDist<Particle>();
//        cdf = new CategoricalDist<Particle>(_particleComparator);
        particles = applyMotionAndSensorModel(obs, timestamp, cdf);
      } else {
        cdf = applySensorModel(particles, obs);
      }

    } else {

      /*
       * perform movement and weighing separately
       */
      if (moveParticles) {
        particles = applyMotionModel(obs, timestamp);
      }

      cdf = applySensorModel(particles, obs);

    }

    /**
     * 3. track the weighted particles (before resampling and normalization)
     */
    _weightedParticles = particles;

    /**
     * 4. store the most likely particle's information
     */

    if (!cdf.canSample())
      throw new ZeroProbabilityParticleFilterException();

    computeBestState(particles);

    /**
     * 5. resample (use the CDF of unevenly weighted particles to create an
     * equal number of equally-weighted ones)
     */
    List<Particle> resampled = cdf.sample(numberOfParticles);
    List<Particle> reweighted = new ArrayList<Particle>(resampled.size());
    for (Particle p : resampled) {
      p = p.cloneParticle();
      p.setWeight(1.0 / resampled.size());
      reweighted.add(p);
    }

    _particles = reweighted;
  }

  private List<Particle> applyMotionAndSensorModel(final OBS obs,
      final double timestamp, final CategoricalDist<Particle> cdf)
      throws ParticleFilterException {
    final double elapsed = timestamp - _timeOfLastUpdate;
    final Collection<Particle> results = new ConcurrentLinkedQueue<Particle>();
    final Collection<Callable<Object>> tasks = new ArrayList<Callable<Object>>();
    for (final Particle p : _particles) {
      tasks.add(Executors.callable(new Runnable() {
        class LocalMoveCache extends ThreadLocal<Collection<Particle>> {
          @Override
          protected Collection<Particle> initialValue() {
            return new ArrayList<Particle>();
          }
        }

        ThreadLocal<Collection<Particle>> threadLocal = new LocalMoveCache();

        @Override
        public void run() {
          final Collection<Particle> moveCache = threadLocal.get();
          _motionModel.move(p, timestamp, elapsed, obs, moveCache);
          for (Particle pm : moveCache) {
            results.add(pm);
            SensorModelResult smr = getParticleLikelihood(pm, obs);
            double likelihood = smr.getProbability();
            if (Double.isNaN(likelihood))
              throw new IllegalStateException("NaN likehood");
            pm.setWeight(likelihood);
            pm.setResult(smr);
          }
          moveCache.clear();
        }
      }));
    }
    try {
      _executorService.invokeAll(tasks);
      for (Particle particle : results) {
        double likelihood = particle.getResult().getProbability();
        cdf.put(likelihood, particle);
      }
    } catch (InterruptedException e) {
      throw new ParticleFilterException(
          "particle motion & sensor apply task interrupted: " + e.getMessage());
    }
    List<Particle> particles = new ArrayList<Particle>(results);
    return particles;
  }

  /**
   * This provides and ordering for particles; needed for reproducibility in
   * sampling. <br>
   * 
   * FIXME this violates the generic contract of Particle by assuming
   * VehicleState data.
   * 
   * @author bwillard
   * 
   */
  class ParticleComparator implements Comparator<Particle> {

    @Override
    public int compare(Particle arg0, Particle arg1) {
      if (arg0 == arg1)
        return 0;

      int partComp = arg0.compareTo(arg1);

      if (partComp != 0)
        return partComp;

      int vehicleComp = ((VehicleState) arg0.getData()).compareTo((VehicleState) arg1.getData());

      if (vehicleComp != 0)
        return vehicleComp;
      
      Particle parent0 = arg0.getParent();
      Particle parent1 = arg1.getParent();
      if (parent0 != null && parent1 != null) {
        int pVehicleComp = ((VehicleState) parent0.getData()).compareTo((VehicleState) parent1.getData());
        if (pVehicleComp != 0)
          return pVehicleComp;
      } else if (parent0 != null && parent1 == null) {
        return 1;
      } else if (parent0 == null && parent1 != null) {
        return -1;
      }

      return 0;
    }

  }

  final ParticleComparator _particleComparator = new ParticleComparator();

  @SuppressWarnings("unused")
  private List<Particle> applyMotionModel(final OBS obs, final double timestamp)
      throws ParticleFilterException {
    List<Particle> particles;
    final double elapsed = timestamp - _timeOfLastUpdate;
    if (_threads > 1) {
      final Collection<Particle> results = new ConcurrentLinkedQueue<Particle>();
      final Collection<Callable<Object>> tasks = new ArrayList<Callable<Object>>();
      for (final Particle p : _particles) {
        tasks.add(Executors.callable(new Runnable() {
          public void run() {
            _motionModel.move(p, timestamp, elapsed, obs, results);
          }
        }));
      }
      try {
        _executorService.invokeAll(tasks);
      } catch (InterruptedException e) {
        throw new ParticleFilterException(
            "particle sensor apply task interrupted: " + e.getMessage());
      }
      particles = new ArrayList<Particle>(results);
    } else {
      particles = new ArrayList<Particle>(_particles.size());
      for (int i = 0; i < _particles.size(); i++) {
        _motionModel.move(_particles.get(i), timestamp, elapsed, obs, particles);
      }
    }
    return particles;
  }

  /**
   * Callable MotionModel task that operates on chunks of particles.
   * 
   */
  public class ParticleMoveTask implements Callable<List<Particle>> {

    private List<Particle> _newParticles;
    private OBS _obs;
    private int _startIdx;
    private int _endIdx;
    private double _elapsed;
    private double _timestamp;

    ParticleMoveTask(int startIdx, int endIdx, OBS obs, double elapsed,
        double timestamp) {
      _newParticles = new ArrayList<Particle>(endIdx - startIdx);
      _obs = obs;
      _elapsed = elapsed;
      _startIdx = startIdx;
      _endIdx = endIdx;
      _timestamp = timestamp;
    }

    @Override
    public List<Particle> call() {
      for (int i = _startIdx; i < _endIdx; i++) {
        _motionModel.move(_particles.get(i), _timestamp, _elapsed, _obs,
            _newParticles);
      }
      return _newParticles;
    }

  }

  /**
   * Finds the most likely/occurring trip & phase combination among the
   * particles, then chooses the particle with highest likelihood of that pair. <br>
   * 
   * FIXME violates generic particle contract by assuming data is of type
   * VehicleState
   * 
   * @param particles
   */
  private void computeBestState(List<Particle> particles) {
    /**
     * We choose the "most likely" particle over the entire distribution w.r.t
     * the inferred trip.
     */
    Map<String, Double> tripPhaseToProb = new HashMap<String, Double>();

    HashMultimap<String, Particle> particlesIdMap = HashMultimap.create();
    Set<Particle> bestParticles = new HashSet<Particle>();
    String bestId = null;

    if (!_maxLikelihoodParticle) {
      double highestTripProb = Double.MIN_VALUE;
      int index = 0;
      for (Particle p : particles) {
        p.setIndex(index++);

        double w = p.getWeight();

        if (w == 0.0)
          continue;

        VehicleState vs = p.getData();
        String tripId = vs.getBlockState() == null
            ? "NA"
            : vs.getBlockState().getBlockLocation().getActiveTrip().getTrip().toString();
        String phase = vs.getJourneyState().toString();
        String id = tripId + "." + phase;

        Double tripPhaseProb = tripPhaseToProb.get(id);

        double newProb;
        if (tripPhaseProb == null) {
          newProb = w;
        } else {
          newProb = (tripPhaseProb == null ? 0.0 : tripPhaseProb) + w;
        }
        tripPhaseToProb.put(id, newProb);
        particlesIdMap.put(id, p);

        /**
         * Check most likely new trip & phase pairs, then find the most likely
         * particle(s) within those.
         */
        if (bestId == null || newProb > highestTripProb) {
          bestId = id;
          highestTripProb = newProb;
        }
      }
      bestParticles.addAll(particlesIdMap.get(bestId));
    } else {
      bestParticles.addAll(particles);
    }

    /**
     * after we've found the best trip & phase pair, we choose the highest
     * likelihood particle among those.
     */
    Particle bestParticle = null;
    for (Particle p : bestParticles) {
      if (bestParticle == null || p.getWeight() > bestParticle.getWeight()) {
        bestParticle = p;
      }
    }

    _mostLikelyParticle = bestParticle.cloneParticle();

  }

  /**
   * Applies the sensor model (as set by setSensorModel) to each particle in the
   * filter, according to the given observable.
   */
  @SuppressWarnings("unused")
  private CategoricalDist<Particle> applySensorModel(List<Particle> particles,
      final OBS obs) throws ParticleFilterException {

    CategoricalDist<Particle> cdf = new CategoricalDist<Particle>();
//    CategoricalDist<Particle> cdf = new CategoricalDist<Particle>(
//        _particleComparator);

    if (_threads > 1) {

      final Collection<Callable<Object>> tasks = new ArrayList<Callable<Object>>();
      final Collection<SensorModelParticleResult> results = new ConcurrentLinkedQueue<SensorModelParticleResult>();
      for (final Particle p : particles) {
        tasks.add(Executors.callable(new Runnable() {
          public void run() {
            results.add(new SensorModelParticleResult(p, getParticleLikelihood(
                p, obs)));
          }
        }));
      }

      try {

        _executorService.invokeAll(tasks);
        for (SensorModelParticleResult res : results) {
          Particle particle = res._particle;
          SensorModelResult result = res._result;
          double likelihood = result.getProbability();

          if (Double.isNaN(likelihood))
            throw new IllegalStateException("NaN likehood");

          particle.setWeight(likelihood);
          particle.setResult(result);

          cdf.put(likelihood, particle);
        }
      } catch (InterruptedException e) {
        throw new ParticleFilterException(
            "particle sensor apply task interrupted: " + e.getMessage());
      }
    } else {
      for (Particle particle : particles) {
        SensorModelResult result = getParticleLikelihood(particle, obs);
        double likelihood = result.getProbability();

        if (Double.isNaN(likelihood))
          throw new IllegalStateException("NaN likehood");

        particle.setWeight(likelihood);
        particle.setResult(result);

        cdf.put(likelihood, particle);
      }
    }

    return cdf;
  }

  public class SensorModelParticleResult {
    public Particle _particle;
    public SensorModelResult _result;

    SensorModelParticleResult(Particle particle, SensorModelResult result) {
      _particle = particle;
      _result = result;
    }

  }

  /**
   * Callable SensorModel task that operates on chunks of particles.
   * 
   */
  public class SensorModelTask implements
      Callable<List<SensorModelParticleResult>> {

    private List<Particle> _newParticles;
    private OBS _obs;
    private int _startIdx;
    private int _endIdx;

    SensorModelTask(List<Particle> newParticles, int startIdx, int endIdx,
        OBS obs) {
      _newParticles = newParticles;
      _obs = obs;
      _startIdx = startIdx;
      _endIdx = endIdx;
    }

    @Override
    public List<SensorModelParticleResult> call() {
      List<SensorModelParticleResult> results = new ArrayList<SensorModelParticleResult>();
      for (int i = _startIdx; i < _endIdx; i++) {
        Particle p = _newParticles.get(i);
        results.add(new SensorModelParticleResult(p, getParticleLikelihood(p,
            _obs)));
      }
      return results;
    }

  }

  private SensorModelResult getParticleLikelihood(Particle particle, OBS obs) {
    return _sensorModel.likelihood(particle, obs);
  }

  public static Boolean getDebugEnabled() {
    return _debugEnabled;
  }
}
