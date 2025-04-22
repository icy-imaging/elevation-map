package plugins.adufour.viewers;

import org.bioimageanalysis.icy.common.listener.ProgressListener;
import org.bioimageanalysis.icy.extension.plugin.abstract_.Plugin;
import org.bioimageanalysis.icy.extension.plugin.annotation_.IcyPluginIcon;
import org.bioimageanalysis.icy.extension.plugin.annotation_.IcyPluginName;
import org.bioimageanalysis.icy.extension.plugin.interface_.PluginCanvas;
import org.bioimageanalysis.icy.gui.canvas.IcyCanvas3D;
import org.bioimageanalysis.icy.gui.frame.progress.ToolTipFrame;
import org.bioimageanalysis.icy.gui.render.IcyVtkPanel;
import org.bioimageanalysis.icy.gui.viewer.Viewer;
import org.bioimageanalysis.icy.model.colormap.IcyColorMap;
import org.bioimageanalysis.icy.model.lut.LUT;
import org.bioimageanalysis.icy.model.lut.LUTListener;
import org.bioimageanalysis.icy.model.render.vtk.VtkUtil;
import org.bioimageanalysis.icy.model.sequence.DimensionId;
import org.bioimageanalysis.icy.model.sequence.Sequence;
import org.bioimageanalysis.icy.model.sequence.SequenceEvent;
import org.bioimageanalysis.icy.model.sequence.SequenceListener;
import org.bioimageanalysis.icy.system.IcyHandledException;
import org.bioimageanalysis.icy.system.thread.ThreadUtil;
import vtk.*;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;

@IcyPluginName("Elevation Map")
@IcyPluginIcon(path = "/plugins/adufour/viewers/elevation-map.png")
public class Elevation extends Plugin implements PluginCanvas<Elevation.ElevationCanvas> {
    @Override
    public String getCanvasClassName() {
        return ElevationCanvas.class.getName();
    }

    @Override
    public ElevationCanvas createCanvas(final Viewer viewer) {
        return new ElevationCanvas(viewer);
    }

    public static class ElevationCanvas extends IcyCanvas3D {
        @Override
        public void keyTyped(final KeyEvent e) {
            super.keyTyped(e);
            if (e.isConsumed()) return;

            if (e.getKeyChar() == 'c' || e.getKeyChar() == 'C') {
                setPositionC((getPositionC() + 1) % getSequence().getSizeC());
                e.consume();
            }
            else if (e.getKeyChar() == '-') {
                warp.SetScaleFactor(warp.GetScaleFactor() * 9 / 10);
                e.consume();
                refresh();
            }
            else if (e.getKeyChar() == '+') {
                warp.SetScaleFactor(warp.GetScaleFactor() * 10 / 9);
                e.consume();
                refresh();
            }
        }

        @Override
        public void mouseWheelMoved(final MouseWheelEvent event) {
            event.consume();

            final double zoomFactor;
            zoomFactor = Math.pow(1.02, (event.getWheelRotation()));
            final vtkCamera camera = vtkPanel.getCamera();

            if (camera.GetParallelProjection() == 1) {
                camera.SetParallelScale(camera.GetParallelScale() / zoomFactor);
            }
            else {
                camera.Dolly(zoomFactor);
                vtkPanel.resetCameraClippingRange();
            }

            refresh();
        }

        // private class ElevationVtkPanel extends IcyVtkPanel
        // {
        // public ElevationVtkPanel()
        // {
        // addKeyListener(this);
        // addMouseWheelListener(this);
        // }
        //
        // @Override
        // public void Delete()
        // {
        // removeMouseWheelListener(this);
        // removeKeyListener(this);
        // disposeInternal();
        // }
        //
        // public void keyTyped(java.awt.event.KeyEvent e)
        // {
        // };
        //
        // @Override
        // public void mouseWheelMoved(MouseWheelEvent e)
        // {
        // e.consume();
        //
        // double zoomFactor;
        // zoomFactor = Math.pow(1.02, (e.getWheelRotation()));
        // if (cam.GetParallelProjection() == 1)
        // {
        // cam.SetParallelScale(cam.GetParallelScale() / zoomFactor);
        // }
        // else
        // {
        // cam.Dolly(zoomFactor);
        // resetCameraClippingRange();
        // }
        //
        // repaint();
        // }
        // }

        final IcyVtkPanel vtkPanel;

        final vtkRenderer vtkRenderer;
        final vtkImageDataGeometryFilter geom;
        final vtkWarpScalar warp;
        final vtkElevationFilter elevationFilter;
        final vtkCubeAxesActor2D axes;
        final vtkLookupTable vtkLUT;
        private vtkImageData imageData;
        final vtkSliderRepresentation2D slider;

        private final LUT userLUT;

        private final LUTListener lutListener = event -> {
            updateVTKLUT();
            refresh();
        };

        private final SequenceListener dataListener = new SequenceListener() {

            @Override
            public void sequenceClosed(final Sequence sequence) {
                sequence.removeListener(this);
            }

            @Override
            public void sequenceChanged(final SequenceEvent sequenceEvent) {
                if (sequenceEvent.getSourceType() == SequenceEvent.SequenceEventSourceType.SEQUENCE_DATA) {
                    updateVtkImageData();
                    refresh();
                }
            }
        };

        private final ToolTipFrame helpBox;

        public ElevationCanvas(final Viewer viewer) {
            super(viewer);

            try {
                geom = new vtkImageDataGeometryFilter();
                warp = new vtkWarpScalar();
                elevationFilter = new vtkElevationFilter();
                axes = new vtkCubeAxesActor2D();
                vtkLUT = new vtkLookupTable();
                imageData = new vtkImageData();
                slider = new vtkSliderRepresentation2D();
            }
            catch (final UnsatisfiedLinkError uslErr) {
                throw new IcyHandledException("The \"Elevation map\" viewer cannot be activated because VTK is not inialised properly.");
            }

            try {
                vtkPanel = new IcyVtkPanel();
            }
            catch (final UnsatisfiedLinkError error) {
                throw new IcyHandledException("Cannot load the elevation map: VTK library not found.");
            }

            vtkRenderer = vtkPanel.getRenderer();

            add(vtkPanel, BorderLayout.CENTER);

            userLUT = getLut();

            setPositionC(0);
            setPositionZ(viewer.getPositionZ());

            updateZNav();
            updateTNav();
            setPositionX(-1);
            setPositionY(-1);

            getMouseImageInfosPanel().setVisible(false);

            final int sizeX = getSequence().getSizeX();
            final int sizeY = getSequence().getSizeY();

            final int minDim = Math.min(sizeX, sizeY);

            final double max = getSequence().getChannelMax(getPositionC());

            // 1) Construct the elevation map from the image data

            // create the geometry from the image data (not the actual data!)
            geom.SetOutputTriangles(1);

            // warp image values to height data using a given scale
            warp.SetInputData(geom.GetOutput());
            // invert the scale to extrude the image toward the camera
            warp.SetScaleFactor(-0.25 / max * minDim);

            elevationFilter.SetInputConnection(warp.GetOutputPort());

            final vtkPolyDataMapper mapper = new vtkPolyDataMapper();
            mapper.SetInputConnection(elevationFilter.GetOutputPort());

            mapper.SetLookupTable(vtkLUT);
            //mapper.SetImmediateModeRendering(1);

            final vtkLODActor actor = new vtkLODActor();
            // optimize mouse dragging by showing only 1/100 of the pixels
            actor.SetNumberOfCloudPoints(sizeX * sizeY / 100);
            actor.SetScale(1, 1, 1);
            actor.SetMapper(mapper);
            vtkRenderer.AddActor(actor);

            // 2) Draw axes around the volume

            axes.SetInputData(elevationFilter.GetPolyDataOutput());
            axes.SetCamera(vtkRenderer.GetActiveCamera());
            axes.SetLabelFormat("%7.0f");
            axes.SetXLabel("X");
            axes.SetYLabel("Y");
            axes.SetZLabel("Intensity");
            axes.SetFlyModeToOuterEdges();// None();// ClosestTriad();
            axes.SetFontFactor(0.9);
            axes.GetProperty().SetColor(1.0, 1.0, 1.0);
            axes.UseBoundsOff();
            axes.UseRangesOn();
            // don't offset the axes => bounds will be aligned with the data
            axes.SetCornerOffset(0);

            vtkRenderer.AddActor(axes);

            vtkRenderer.SetBackground(0, 0, 0);

            // update the LUT one first time...

            final LUT.LUTChannel currentLUT = userLUT.getLutChannel(getPositionC());
            final IcyColorMap map = currentLUT.getColorMap();
            vtkLUT.SetNumberOfColors(IcyColorMap.SIZE);
            for (int i = 0; i < IcyColorMap.SIZE; i++)
                vtkLUT.SetTableValue(i, map.red.mapf[i], map.green.mapf[i], map.blue.mapf[i], 1.0);
            updateVTKLUT();

            // ... and every time the user LUT changes
            userLUT.addListener(lutListener);

            vtkRenderer.GetActiveCamera().SetViewUp(0, -1, 0);
            vtkRenderer.ResetCamera();
            vtkRenderer.GetActiveCamera().Elevation(180);
            vtkRenderer.ResetCameraClippingRange();

            String helpText = "<b>How to use the elevation map:</b><br><ul>";
            helpText += " <li>interact with the mouse and colormap just like in the 2D viewer<br>";
            helpText += " <li>press 'c' to toggle between channels<br>";
            helpText += " <li>press '+' or '-' to increase or decrease the depth";
            helpText += "</ul><i>(click on this message box to dismiss it)</i>";

            helpBox = new ToolTipFrame(helpText);

            // Update every time the sequence data changes (for real-time visualization)
            getSequence().addListener(dataListener);
        }

        @Override
        public void refresh() {
            vtkPanel.repaint();
        }

        @Override
        protected void positionChanged(final DimensionId dim) {
            updateVtkImageData();

            if (dim == DimensionId.C) updateVTKLUT();

            refresh();
        }

        @Override
        public Component getViewComponent() {
            return vtkPanel;
        }

        @Override
        public BufferedImage getRenderedImage(final int t, final int z, final int c, final boolean canvasView) {
            final int oldT = getPositionT();
            final int oldZ = getPositionZ();
            final int oldC = getPositionC();

            setPositionT(t);
            setPositionZ(z);
            setPositionC(c);

            try {
                ThreadUtil.sleep(1000);
                return getRenderedImage();
            }
            finally {
                setPositionT(oldT);
                setPositionZ(oldZ);
                setPositionC(oldC);
            }
        }

        @Override
        public Sequence getRenderedSequence(final boolean canvasView, final ProgressListener progressListener) {
            final Sequence screenshot = new Sequence("Screenshot of " + getSequence().getName() + " (channel " + getSequence().getChannelName(getPositionC()) + ")");

            if (getSequence().getSizeZ() == 1 && getSequence().getSizeT() == 1) {
                screenshot.setImage(0, 0, getRenderedImage());
                return screenshot;
            }

            final int oldT = getPositionT();
            final int oldZ = getPositionZ();

            try {
                for (int t = 0; t < getSequence().getSizeT(); t++)
                    for (int z = 0; z < getSequence().getSizeZ(); z++) {
                        setPositionTInternal(t);
                        setPositionZInternal(z);
                        positionChanged(DimensionId.NULL);
                        ThreadUtil.sleep(1000);
                        screenshot.setImage(t, z, getRenderedImage());
                    }
                return screenshot;
            }
            finally {
                setPositionT(oldT);
                setPositionZ(oldZ);
            }
        }

        public BufferedImage getRenderedImage() {
            final int w = vtkPanel.getWidth();
            final int h = vtkPanel.getHeight();
            final BufferedImage shot = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            vtkPanel.paint(shot.createGraphics());
            return shot;
        }

        private void updateVtkImageData() {
            final Sequence seq = getSequence();
            if (getPositionC() == -1 || getPositionZ() == -1 || getPositionT() == -1) return;

            imageData = VtkUtil.getImageData(seq.getDataXY(getPositionT(), getPositionZ(), getPositionC()), seq.getDataType(), seq.getSizeX(), seq.getSizeY(), seq.getSizeZ(), 1);
            geom.SetInputData(imageData);
            geom.Update();

            // update range axes
            axes.SetRanges(0, getSequence().getSizeX(), 0, getSequence().getSizeY(), getCurrentImage().getChannelMax(0), getSequence().getChannelMin(0));
        }

        private void updateVTKLUT() {
            final LUT.LUTChannel currentLUT = userLUT.getLutChannel(getPositionC());

            final IcyColorMap map = currentLUT.getColorMap();

            final double newMin = currentLUT.getMin() * warp.GetScaleFactor();
            final double newMax = currentLUT.getMax() * warp.GetScaleFactor();

            final double[] rgba = new double[4];
            for (int i = 0; i < IcyColorMap.SIZE; i++) {
                rgba[0] = map.red.mapf[i];
                rgba[1] = map.green.mapf[i];
                rgba[2] = map.blue.mapf[i];
                rgba[3] = map.alpha.mapf[i];
                vtkLUT.SetTableValue(i, rgba);
            }
            elevationFilter.SetLowPoint(0, 0, newMin);
            elevationFilter.SetHighPoint(0, 0, newMax);
        }

        @Override
        public void shutDown() {
            userLUT.removeListener(lutListener);
            if (getSequence() != null) getSequence().removeListener(dataListener);

            vtkPanel.disposeInternal();

            helpBox.close();

            super.shutDown();
        }

        /**
         * Called by the parent viewer when building the toolbar.<br>
         * This way the canvas can customize it by adding specific command for instance.<br>
         *
         * @param toolBar the parent toolbar to customize
         */
        @Override
        public void customizeToolbar(final JToolBar toolBar) {
            // Nothing to do
        }
    }
}