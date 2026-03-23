# Shortest Path Navigation — Fix Plan

## Root Cause

The "📏 Shortest" route preference in the app **does nothing**. Here's why:

### How GH 6.2 Routing Works
1. `request.setProfile("car")` → looks up profile by name from graph's `properties` file
2. `DefaultWeightingFactory.createWeighting(profile, hints)` → reads `profile.getWeighting()` → always "fastest"
3. The `request.hints.putObject("weighting", "shortest")` hint **is ignored** — line 79 of `DefaultWeightingFactory.java` reads weighting from the profile object, not from merged hints
4. Result: "shortest" preference silently falls back to "fastest" every time

### Graph Config (current)
```yaml
profiles:
  - name: car
    vehicle: car
    weighting: fastest
```

Only one profile. GH requires profile names to match exactly — there's no runtime weighting override.

## Solution Options

### Option A: Rebuild Graph with Two Profiles (Recommended)
Add a second profile to `graphhopper-config.yml`:
```yaml
profiles:
  - name: car
    vehicle: car
    weighting: fastest
  - name: car-shortest
    vehicle: car
    weighting: shortest
```

Then in the app:
- `RoutePreference.FASTEST` → `request.setProfile("car")`
- `RoutePreference.SHORTEST_DISTANCE` → `request.setProfile("car-shortest")`

**Pros**: Clean, correct, uses GH's built-in ShortestWeighting (distance-only, ignores speed)
**Cons**: Requires rebuilding the graph (~10 min from FL PBF), graph size increases ~30-50%, must re-push routing files to both devices

### Option B: CustomModel with Distance Priority (No Graph Rebuild)
Use GH 6.2's CustomModel/CustomWeighting to create a distance-prioritized route at query time:
```kotlin
val customModel = CustomModel()
customModel.setDistanceInfluence(100.0) // heavily penalize distance
request.setCustomModel(customModel)
```

**Pros**: No graph rebuild needed
**Cons**: Requires `CustomProfile` in graph config (which we don't have), so this also needs a rebuild. Also less accurate than true shortest weighting.

### Option C: Post-Process Multiple Routes (Workaround)
Request 3 alternative routes with `algorithm=alternative_route`, then select the one with minimum total distance.

**Pros**: Works with current graph immediately, no rebuild
**Cons**: Not a true shortest path — just picks the shortest among a few alternatives. May not find the actual shortest route. Also slower (3x routing).

## Recommendation

**Option A** is the correct fix. It's how GH is designed to work.

### Implementation Steps
1. Update `tools/graphhopper-config.yml` — add `car-shortest` profile
2. Rebuild graph: `./tools/build_routing_graph.sh` (~10 min)
3. Stage new graph files on both devices (`/data/local/tmp/routing_*`)
4. Update `NavigationViewModel.profileFor()`: `SHORTEST_DISTANCE -> "car-shortest"`
5. Remove the `weightingFor()` function and all `weighting` hint code (it never worked)
6. Update tests
7. Push to devices and verify

### Graph Size Impact
- Current: ~222MB (car-fastest only)
- With car-shortest: ~300-350MB estimated (shared edge data, separate weighting data)

### ShortestWeighting Behavior (from GH source)
```java
public double calcEdgeWeight(EdgeIteratorState edgeState, boolean reverse) {
    return edgeState.getDistance(); // uses pure distance, ignores speed
}
```
This means shortest will:
- Route through residential streets if they're geometrically shorter
- Ignore speed limits entirely
- Potentially route through slow roads if they cut distance
- NOT consider time at all
