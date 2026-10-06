package app.bililisten.storage

import android.media.MediaExtractor
import android.media.MediaFormat
import app.bililisten.shared.TransferFailure
import java.io.File

object LocalAudioVerifier {
    /** Reads local container/sample metadata only; never contacts a license server or decrypts media. */
    fun check(file:File,durationMs:Long) {
        val extractor=MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            if(extractor.trackCount!=1 || !extractor.psshInfo.isNullOrEmpty() || extractor.drmInitData!=null || extractor.getCasInfo(0)!=null)
                throw TransferFailure("文件包含其他轨道或加密信息，不能作为普通离线音频")
            val format=extractor.getTrackFormat(0)
            if(format.getString(MediaFormat.KEY_MIME)!="audio/mp4a-latm")throw TransferFailure("文件不是支持的独立普通 AAC 音频")
            val actual=if(format.containsKey(MediaFormat.KEY_DURATION))format.getLong(MediaFormat.KEY_DURATION)/1000 else -1
            if(actual<=0 || kotlin.math.abs(actual-durationMs)>2000)throw TransferFailure("音频时长与完整分 P 不符，未标记为完成")
            extractor.selectTrack(0)
            var samples=0;var last=0L
            while(extractor.sampleTime>=0) {
                if(Thread.currentThread().isInterrupted)throw InterruptedException()
                if(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED!=0)throw TransferFailure("音频包含加密样本，暂不支持离线保存")
                last=extractor.sampleTime/1000;samples++
                if(!extractor.advance())break
            }
            if(samples==0 || kotlin.math.abs(last-durationMs)>2500)throw TransferFailure("音频样本不完整，未标记为完成")
        } finally {extractor.release()}
    }
}
