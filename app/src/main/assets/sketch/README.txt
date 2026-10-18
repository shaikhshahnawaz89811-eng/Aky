Sketch Studio, stage 3: the optional Three.js library
=====================================================

The 3D scene option of Sketch Studio needs ONE file here:

    app/src/main/assets/sketch/three.min.js

It is NOT part of this project (it could not be downloaded where stage 3 was written, and nothing is faked in its place).
Without it the studio shows "3D scene (three.min.js nahi mila)" and refuses to make 3D pages; web pages (HTML / CSS / JS)
work without it.

How to add it
-------------
1. Get a classic build of three.js, for example r128 (about 0.6 MB): the file "three.min.js" from the three.js release
   (https://github.com/mrdoob/three.js, folder build/ of an older release, or the npm package "three" before r160, file
   build/three.min.js). Use a build that defines the global THREE (a plain script, not an ES module).
2. Copy it to the path above and rebuild the app. The file must be smaller than 2,000,000 bytes (HtmlSandbox.THREE_MAX_BYTES).
3. Check on the phone (BUILD_NOTES, "Phone check for stage 3", step 9 to 11): a 3D scene appears, WebGL works, RAM is fine.

How it is used
--------------
The studio puts the library text in front of the page that the model wrote, inside the same sandbox as every web page
(no network, no app bridge, see ai/HtmlSandbox.kt). The model is told THREE is already loaded and must not load anything.
A 3D page exported with "Share HTML file" carries the library inside it, so it also opens on its own in a browser.

Not measured yet: WebGL on the target phone, RAM use, frame rate, APK size (+0.6 MB).
