# SolarUtils
### W.I.P

# Developer Notes

### Celestial Dynamics Model
References:  
https://ssd.jpl.nasa.gov/planets/approx_pos.html
https://stjarnhimlen.se/comp/ppcomp.html
https://astropedia.astrogeology.usgs.gov/download/Docs/WGCCRE/WGCCRE2015reprint.pdf

### Kool Submodule
To update [Kool](https://github.com/kool-engine/kool) submodule:
```shell
git submodule update --remote --force
```

### Assets
Some assets (in `/assets/generated/`) are generated by the `generateAssets` gradle task.  
These are tracked by version control since `generateAssets` only works on windows.

Assets are "deployed" (included in builds) by the `deployAssets` gradle task, this task handles merging static and generated assets and deploying them.