# Afterglow

A Clojure approach to creating dynamic light shows, leveraging the
[Open Lighting Architecture](https://www.openlighting.org/ola/), and
pieces of the [Overtone](https://github.com/overtone/overtone)
toolkit. For efficiency, Afterglow uses
[Protocol Buffers](https://developers.google.com/protocol-buffers/docs/overview)
to communicate with the `olad` process running on the local machine
via its
[RPC Service](https://docs.openlighting.org/doc/latest/rpc_system.html).

## Status

I am very rapidly fleshing this out; it has started to develop some
really fun features, but a majority remains in my to-do lists and in
my head. The examples are already starting to be intriguing and
informative, but there will be more, and better documentation, very
soon. In particular, the modeling of fixtures, channels, etc. is in an
early form now, and there have been drastic changes as I gain
experience with how I want to use them, and build macros and other
tools to make them easier to define. They are starting to feel about
right, but I need to flesh out a few more pieces before it is ready
for a first release.

## Installation

1. [Install OLA](https://www.openlighting.org/ola/getting-started/downloads/);
   I recommend using [Homebrew](http://brew.sh) which lets you simply
   `brew install ola`. Once you launch the `olad` server you can
   interact with its embedded
   [web server](http://localhost:9090/ola.html), which is very helpful
   in seeing whether anything is working; you can even watch live DMX
   values changing.
2. For now set up a Clojure project using [Leiningen](http://leiningen.org).
3. Add this project as a dependency:
   [![Clojars Project](http://clojars.org/afterglow/latest-version.svg)](http://clojars.org/afterglow)

Eventually you may be able to download binary distributions from somewhere.

## Usage

Given its current development phase, you will want to use Afterglow in a Clojure repl.

> The next two lines are not needed if you are using Leiningen to get your repl,
> since the project is configured to start you in this namespace for convenience.

    (require 'afterglow.examples)
    (in-ns 'afterglow.examples)
    
Start the sample show which runs on DMX universe 1. You will want to
have OLA configured to at least have an ArtNet universe with that ID
so you can watch the DMX values using its web interface. It would be
even better if you had an actual DMX interface hooked up, and changed
the definition of `sample-rig` to include some real lights you have
connected. Either way, here is how you start the show sending control
signals to lights:

    (show/start! sample-show)
    
The `afterglow.examples` namespace has already assigned a nice cool
blue color to all lights in the sample show and set their dimmers to
full, using these two lines:

    (show/add-function! sample-show :color blue-cue)
    (show/add-function! sample-show :master (master-cue 255))

So if you happened to have the same fixtures hooked up, assigned the
same DMX addresses as I did when I wrote this, you would see a bunch
of blue light. Realistically, you can be able to navigate to the
`olad` embedded [web server](http://localhost:9090/ola.html) and see
the non-zero DMX values in the blue and dimmer channels, assuming you
have set up a Universe with ID 1.
    
We can set the lights a little dimmer...

    (show/add-function! sample-show :master (master-cue 200))

> Adding a function with the same keyword as an existing function replaces
> the old one. The dimmer channels drop from 255 to 200.
    
Change the color to orange:

    (show/add-function! sample-show :color (global-color-cue :orange))

> The color channel values change.
    
Let's get a little fancy and ramp the dimmers up on a sawtooth curve each beat:

    (show/add-function! sample-show :master
                        (master-cue (params/build-oscillated-param sample-show
                                     (oscillators/sawtooth-beat))))
    
Slow that down a little:

    (afterglow.rhythm/metro-bpm (:metronome sample-show) 70)
    
> If you have a web browser open on
> [your OLA daemon](http://localhost:9090/ola.html)'s DMX monitor for
> Universe 1, you will see the values for channels changing, then
> ramping up quickly, then a little more slowly after you change the
> BPM. OLA 0.9.5 introduced a new, beta web UI based on AngularJS which
> you can access through a small [New UI (Beta)](http://localhost:9090/new/)
> link at the bottom of the page. In my experience, it has been
> completely stable, looks a lot better, and is *far* more dynamic
> and responsive at monitoring changing DMX values, and presenting
> them in an intuitive at-a-glance way.

If you can, alter the example to use a universe and channels that you
will actually be able to see with a connected fixture, and watch
Clojure seize control of your lights!

### Further Experiments

If you have DJ software or a mixer sending you MIDI clock data, you
can sync the show's BPM to it (see the
[wiki](https://github.com/brunchboy/afterglow/wiki/MIDI-Mapping-and-Beat-Sync#syncing-to-midi-clock)
for details):

    (show/sync-to-external-clock sample-show
                                 (afterglow.midi/sync-to-midi-clock "traktor"))

How about a nice cycling rainbow color fade?

    (def hue-param (params/build-oscillated-param
      sample-show (oscillators/sawtooth-bar) :max 360))
    (show/add-function! sample-show :color
      (global-color-cue
        (params/build-color-param sample-show :s 100 :l 50 :h hue-param)))

> The Wiki has
> [more examples](https://github.com/brunchboy/afterglow/wiki/Effect-Examples)
> of building effects, and mapping parameters to MIDI controllers.
> When you are all done, you can:

Terminate the effect handler thread...

    (show/stop! sample-show)
    
And darken the universe we were playing with.

    (show/blackout-show sample-show)

> An alternate way of accomplishing those last two steps would have been to call
> `(show/clear-functions! sample-show)` before `show/stop!` because once there
> were no effect functions, all the DMX values would settle back at zero and
> stay there until you stopped the show.

## Options

TODO: listing of options this app accepts once it can run as a standalone app.

### Bugs

...

### Ideas

* Model moving head location and position, so they can be panned and aimed in a coordinated way.
    - [Wikipedia](http://en.wikipedia.org/wiki/Rotation_formalisms_in_three_dimensions) has the most promising overview of what I need to do.
    - If I can’t find anything Clojure or Java native, [this C# library](http://www.codeproject.com/Articles/17425/A-Vector-Type-for-C) might serve as a guide.
    - Or perhaps [this paper](https://www.fastgraph.com/makegames/3drotation/) with its associated C++ source.
    - Or [this one](http://inside.mines.edu/fs_home/gmurray/ArbitraryAxisRotation/) which is already Java but seems to only perform, not calculate, rotations.
    - Use iOS device to help determine orientation of fixture: Hold phone upright facing stage from audience perspective to set reference attitude; move to match a landmark on the fixture (documented in the fixture definition), and have phone use [CoreMotion](https://developer.apple.com/library/ios/documentation/CoreMotion/Reference/CMAttitude_Class/index.html#//apple_ref/occ/instm/CMAttitude/multiplyByInverseOfAttitude:) `CMAttitude` `multiplyByInverseOfAttitude` to determine the difference.
    - The more I investigate, the more it looks like [Java3D’s](http://docs.oracle.com/cd/E17802_01/j2se/javase/technologies/desktop/java3d/forDevelopers/J3D_1_3_API/j3dapi/) [Transform3D](http://docs.oracle.com/cd/E17802_01/j2se/javase/technologies/desktop/java3d/forDevelopers/J3D_1_3_API/j3dapi/javax/media/j3d/Transform3D.html) object is going to handle it for me, which is very convenient, as it is already available in Clojure. To combine transformations, just multiply them together (with the `mul` method).
* Sparkle effect, essentially a particle generator with configurable maximum brightness, fade time, distribution. Work both with arbitrary channel list, and with spatially mapped origin/density; as single intensity, or spatially mapped hue/saturation patterns.
* Use [claypoole](https://clojars.org/com.climate/claypoole) for parallelism.
* Add OSC support (probably using [Overtone&rsquo;s implementation](https://github.com/rosejn/osc-clj)) for controller support, and MIDI as well.
* Rich controller support for [Ableton Push](https://forum.ableton.com/viewtopic.php?f=55&t=193744)!
* Add a user interface using [Luminus](http://www.luminusweb.net/docs).
* Serious references for color manipulation, but in [Julia](https://github.com/timholy/Color.jl).
* Absolutely amazing reference on [color vision](http://handprint.com/LS/CVS/color.html)! Send him a note asking if he knows where I can find an algorithm for using arbitrary LEDs to make an HSL color!
* When it is time to optimize performance, study the [type hints](http://clojure.org/java_interop#Java%20Interop-Type%20Hints) interop information looks very informative and helpful.
* Eventually create a leiningen task that can build a standalone jar with Afterglow and a custom show definition file and its supporting resources, so developers can easily deploy and share shows with non-Clojurists.
* Once I release the first version, answer this StackOverflow [question](http://stackoverflow.com/questions/9582192/dmx-software-to-control-lights-with-programmable-interface).

### References

* Clojure implementation of Protocol Buffers via [lein-protobuf](https://github.com/flatland/lein-protobuf) and [clojure-protobuf](https://github.com/flatland/clojure-protobuf).
* The incomplete [Java OLA client](https://github.com/OpenLightingProject/ola/tree/master/java).

### Related Work

* Am fixing the [broken Max external](https://wiki.openlighting.org/index.php/OlaOutput_Max_External). It fails because it tries to load an outdated version of the `libproto` DLL in a hardcoded bad library path. I have now been able to check out the source into `old/svn/olaoutput-read-only` and succeeded at building and fixing it. I separately downloaded the [Max 6.1.4 SDK](https://cycling74.com/downloads/sdk/). The maxhelp file has some seeming errors in it: a "state" message which seems unimplemented, and a second inlet which appears to really just be an argument. I have offered to share my changes and explore fixing the help unless the authors want to, on the open lighting Google group. They have, at my prompting, migrated to github, and I am committing my changes to a fork, in `git/olaoutput`.

## License

Copyright © 2015 [Deep Symmetry, LLC](http://deepsymmetry.org)

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
