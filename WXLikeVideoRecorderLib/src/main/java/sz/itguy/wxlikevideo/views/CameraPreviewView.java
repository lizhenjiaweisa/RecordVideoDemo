package sz.itguy.wxlikevideo.views;

import android.content.Context;
import android.hardware.Camera;
import android.os.Handler;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.FrameLayout;

import java.lang.ref.WeakReference;

import sz.itguy.wxlikevideo.camera.CameraHelper;

/**
 * 相机预览视图
 *
 * @author Martin
 */
public class CameraPreviewView extends FrameLayout {

    private OnPreviewChangedListener mOnPreviewChangedListener;

    private Camera mCamera;
    private float viewWHRatio;

    public CameraPreviewView(Context context) {
        this(context, null);
    }

    public CameraPreviewView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CameraPreviewView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    /**
     * 获取相机
     * @return
     */
    public Camera getCamera() {
        return mCamera;
    }

    /**
     * 设置相机（初始化后必须调用，否则没有预览效果）
     * @param camera
     */
    public void setCamera(Camera camera) {
        addView(new RealCameraPreviewView(getContext(), camera));
        mCamera = camera;
    }

    public void setOnPreviewChangedListener(OnPreviewChangedListener onPreviewChangedListener) {
        mOnPreviewChangedListener = onPreviewChangedListener;
    }

    public void setViewWHRatio(float viewWHRatio) {
        this.viewWHRatio = viewWHRatio;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = (int) (width / viewWHRatio);
        int wms = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY);
        int hms = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY);
        super.onMeasure(wms, hms);
    }

    /**
     * 实际相机预览视图
     *
     * @author Martin
     */
    private class RealCameraPreviewView extends SurfaceView implements SurfaceHolder.Callback {

        private static final String TAG = "NewCameraPreview";

        // 用于判断双击事件的两次按下事件的间隔
        private static final long DOUBLE_CLICK_INTERVAL = 200;

        private Camera mCamera;

        private long mLastTouchDownTime;

        private ZoomRunnable mZoomRunnable;

        public RealCameraPreviewView(Context context, Camera camera) {
            super(context);
            mCamera = camera;
            // Install a SurfaceHolder.Callback so we get notified when the
            // underlying surface is created and destroyed.
            getHolder().addCallback(this);
//        // deprecated setting, but required on Android versions prior to 3.0
//        mHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            Camera.Size size = mCamera.getParameters().getPreviewSize();
            float ratio = 1f * size.height / size.width;
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int height = (int) (width / ratio);
            int wms = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY);
            int hms = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY);
            super.onMeasure(wms, hms);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    if (mCamera.getParameters().isZoomSupported() && event.getDownTime() - mLastTouchDownTime <= DOUBLE_CLICK_INTERVAL) {
                        zoomPreview();
                    }
                    mLastTouchDownTime = event.getDownTime();
                    float x = event.getX();
                    float y = event.getY();
                    autoFocus();
                    break;
            }
            return super.onTouchEvent(event);
        }

        /**
         * 放大预览视图
         */
        private void zoomPreview() {
            Camera.Parameters parameters = mCamera.getParameters();
            int currentZoom = parameters.getZoom();
            int maxZoom = (int) (parameters.getMaxZoom() / 2f + 0.5);
            int destZoom = 0 == currentZoom ? maxZoom : 0;
            if (parameters.isSmoothZoomSupported()) {
                mCamera.stopSmoothZoom();
                mCamera.startSmoothZoom(destZoom);
            } else {
                Handler handler = getHandler();
                if (null == handler)
                    return;
                handler.removeCallbacks(mZoomRunnable);
                handler.post(mZoomRunnable = new ZoomRunnable(destZoom, currentZoom, mCamera));
            }
        }

        /**
         * 自动对焦
         */
        public void autoFocus() {
            mCamera.cancelAutoFocus();
            mCamera.autoFocus(null);
        }

        public void surfaceCreated(SurfaceHolder holder) {
            Log.d(TAG, "surfaceCreated");
        }

        public void surfaceDestroyed(SurfaceHolder holder) {
            // empty. Take care of releasing the Camera preview in your activity.
            Log.d(TAG, "surfaceDestroyed");
        }

        public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
            Log.d(TAG, "surfaceChanged w: " + w + "---h: " + h);

            // If your preview can change or rotate, take care of those events here.
            // Make sure to stop the preview before resizing or reformatting it.

            if (holder.getSurface() == null){
                // preview surface does not exist
                return;
            }

            // stop preview before making changes
            try {
                mCamera.stopPreview();
            } catch (Exception e){
                // ignore: tried to stop a non-existent preview
            }

            // set preview size and make any resize, rotate or
            // reformatting changes here
            Camera.Parameters parameters = mCamera.getParameters();
            Camera.Size size = CameraHelper.getOptimalPreviewSize(parameters.getSupportedPreviewSizes(), Math.min(w, h));
            parameters.setPreviewSize(size.width, size.height);
            mCamera.setParameters(parameters);

            if (null != mOnPreviewChangedListener)
                mOnPreviewChangedListener.onPreStart();

            // start preview with new settings
            try {
                mCamera.setPreviewDisplay(holder);
                mCamera.startPreview();
            } catch (Exception e){
                Log.d(TAG, "Error starting camera preview: " + e.getMessage());
            }
        }

    }

    /**
     * 放大预览视图任务
     *
     * @author Martin
     */
    private static class ZoomRunnable implements Runnable {

        int destZoom, currentZoom;
        WeakReference<Camera> cameraWeakRef;

        public ZoomRunnable(int destZoom, int currentZoom, Camera camera) {
            this.destZoom = destZoom;
            this.currentZoom = currentZoom;
            cameraWeakRef = new WeakReference<>(camera);
        }

        @Override
        public void run() {
            Camera camera = cameraWeakRef.get();
            if (null == camera)
                return;

            boolean zoomUp = destZoom > currentZoom;
            for (int i = currentZoom; zoomUp ? i <= destZoom : i >= destZoom; i = (zoomUp ? ++i : --i)) {
                Camera.Parameters parameters = camera.getParameters();
                parameters.setZoom(i);
                camera.setParameters(parameters);
            }
        }
    }

    /**
     * 预览画面改变监听器
     *
     * @author Martin
     */
    public interface OnPreviewChangedListener {

        void onPreStart();

    }

}