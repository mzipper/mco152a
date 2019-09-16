
package main.java;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Date;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.persistence.EntityManager;
import javax.swing.JOptionPane;
import javax.swing.SwingWorker;

import main.Util;
import main.persist.DiskRun;
import main.persist.EM;
import main.ui.Gui;

/**
 * Class that creates the thread that actually does the disk writing and a lot more...
 *
 */
public class DiskWorker extends SwingWorker <Boolean, DiskMark> {
    
    @Override
    protected Boolean doInBackground() throws Exception {
        
        System.out.println("*** starting new worker thread");
        App.msg("Running readTest "+App.readTest+"   writeTest "+App.writeTest);
        App.msg("num files: "+App.numOfMarks+", num blks: "+App.numOfBlocks
           +", blk size (kb): "+App.blockSizeKb+", blockSequence: "+App.blockSequence);
        
        int wUnitsComplete = 0,
            rUnitsComplete = 0,
            unitsComplete;
        
        int wUnitsTotal = App.writeTest ? App.numOfBlocks * App.numOfMarks : 0;
        int rUnitsTotal = App.readTest ? App.numOfBlocks * App.numOfMarks : 0;
        int unitsTotal = wUnitsTotal + rUnitsTotal;
        float percentComplete;
        
        int blockSize = App.blockSizeKb* App.KILOBYTE;
        byte [] blockArr = new byte [blockSize];
        for (int b=0; b<blockArr.length; b++) {
            if (b%2==0) {
                blockArr[b]=(byte)0xFF;
            }
        }
   
        DiskMark wMark, rMark;
        
        Gui.updateLegend();
        
        if (App.autoReset == true) {
            App.resetTestData();
            Gui.resetTestData();
        }
        
        int startFileNum = App.nextMarkNumber;
        
        if(App.writeTest) {
            DiskRun run = new DiskRun(DiskRun.IOMode.WRITE, App.blockSequence);
            run.setNumMarks(App.numOfMarks);
            run.setNumBlocks(App.numOfBlocks);
            run.setBlockSize(App.blockSizeKb);
            run.setTxSize(App.targetTxSizeKb());
            run.setDiskInfo(Util.getDiskInfo(App.dataDir));
            
            App.msg("disk info: ("+ run.getDiskInfo()+")");
            
            Gui.chartPanel.getChart().getTitle().setVisible(true);
            Gui.chartPanel.getChart().getTitle().setText(run.getDiskInfo());
            
            if (App.multiFile == false) {
                App.testFile = new File(App.dataDir.getAbsolutePath()+File.separator+"testdata.jdm");
            }            
            for (int m=startFileNum; m<startFileNum+App.numOfMarks && !isCancelled(); m++) {
                
                if (App.multiFile == true) {
                    App.testFile = new File(App.dataDir.getAbsolutePath()
                            + File.separator+"testdata"+m+".jdm");
                }   
                wMark = new DiskMark(DiskMark.MarkType.WRITE);
                wMark.setMarkNum(m);
                long startTime = System.nanoTime();
                long totalBytesWrittenInMark = 0;

                String mode = "rw";
                if (App.writeSyncEnable) { mode = "rwd"; }
                
                try {
                    try (RandomAccessFile rAccFile = new RandomAccessFile(App.testFile,mode)) {
                        for (int b = 0; b< App.numOfBlocks; b++) {
                            if (App.blockSequence == DiskRun.BlockSequence.RANDOM) {
                                int rLoc = Util.randInt(0, App.numOfBlocks-1);
                                rAccFile.seek(rLoc*blockSize);
                            } else {
                                rAccFile.seek(b*blockSize);
                            }
                            rAccFile.write(blockArr, 0, blockSize);
                            totalBytesWrittenInMark += blockSize;
                            wUnitsComplete++;
                            unitsComplete = rUnitsComplete + wUnitsComplete;
                            percentComplete = (float)unitsComplete/(float)unitsTotal * 100f;
                            setProgress((int)percentComplete);
                        }
                    }
                } catch (FileNotFoundException ex) {
                    Logger.getLogger(App.class.getName()).log(Level.SEVERE, null, ex);
                } catch (IOException ex) {
                    Logger.getLogger(App.class.getName()).log(Level.SEVERE, null, ex);
                }
                long endTime = System.nanoTime();
                long elapsedTimeNs = endTime - startTime;
                double sec = (double)elapsedTimeNs / (double)1000000000;
                double mbWritten = (double)totalBytesWrittenInMark / (double) App.MEGABYTE;
                wMark.setBwMbSec(mbWritten / sec);
                App.msg("m:"+m+" write IO is "+wMark.getBwMbSecAsString()+" MB/s     "
                        + "("+Util.displayString(mbWritten)+ "MB written in "
                        + Util.displayString(sec)+" sec)");
                App.updateMetrics(wMark);
                publish(wMark);
                
                run.setRunMax(wMark.getCumMax());
                run.setRunMin(wMark.getCumMin());
                run.setRunAvg(wMark.getCumAvg());
                run.setEndTime(new Date());
            }
            
            EntityManager em = EM.getEntityManager();
            em.getTransaction().begin();
            em.persist(run);
            em.getTransaction().commit();
            
            Gui.runPanel.addRun(run);
        }
        
        
        // try renaming all files to clear catch
        if (App.readTest && App.writeTest && !isCancelled()) {
            JOptionPane.showMessageDialog(Gui.mainFrame, 
                "For valid READ measurements please clear the disk cache by\n" +
                "using the included RAMMap.exe or flushmem.exe utilities.\n" +
                "Removable drives can be disconnected and reconnected.\n" +
                "For system drives use the WRITE and READ operations \n" +
                "independantly by doing a cold reboot after the WRITE",
                "Clear Disk Cache Now",JOptionPane.PLAIN_MESSAGE);
        }
        
        if (App.readTest) {
            DiskRun run = new DiskRun(DiskRun.IOMode.READ, App.blockSequence);
            run.setNumMarks(App.numOfMarks);
            run.setNumBlocks(App.numOfBlocks);
            run.setBlockSize(App.blockSizeKb);
            run.setTxSize(App.targetTxSizeKb());
            run.setDiskInfo(Util.getDiskInfo(App.dataDir));
              
            App.msg("disk info: ("+ run.getDiskInfo()+")");
            
            Gui.chartPanel.getChart().getTitle().setVisible(true);
            Gui.chartPanel.getChart().getTitle().setText(run.getDiskInfo());
            
            for (int m=startFileNum; m<startFileNum+App.numOfMarks && !isCancelled(); m++) {
                
                if (App.multiFile == true) {
                    App.testFile = new File(App.dataDir.getAbsolutePath()
                            + File.separator+"testdata"+m+".jdm");
                }
                rMark = new DiskMark(DiskMark.MarkType.READ);
                rMark.setMarkNum(m);
                long startTime = System.nanoTime();
                long totalBytesReadInMark = 0;

                try {
                    try (RandomAccessFile rAccFile = new RandomAccessFile(App.testFile,"r")) {
                        for (int b = 0; b< App.numOfBlocks; b++) {
                            if (App.blockSequence == DiskRun.BlockSequence.RANDOM) {
                                int rLoc = Util.randInt(0, App.numOfBlocks-1);
                                rAccFile.seek(rLoc*blockSize);
                            } else {
                                rAccFile.seek(b*blockSize);
                            }
                            rAccFile.readFully(blockArr, 0, blockSize);
                            totalBytesReadInMark += blockSize;
                            rUnitsComplete++;
                            unitsComplete = rUnitsComplete + wUnitsComplete;
                            percentComplete = (float)unitsComplete/(float)unitsTotal * 100f;
                            setProgress((int)percentComplete);
                        }
                    }
                } catch (FileNotFoundException ex) {
                    Logger.getLogger(App.class.getName()).log(Level.SEVERE, null, ex);
                } catch (IOException ex) {
                    Logger.getLogger(App.class.getName()).log(Level.SEVERE, null, ex);
                }
                long endTime = System.nanoTime();
                long elapsedTimeNs = endTime - startTime;
                double sec = (double)elapsedTimeNs / (double)1000000000;
                double mbRead = (double) totalBytesReadInMark / (double) App.MEGABYTE;
                rMark.setBwMbSec(mbRead / sec);
                App.msg("m:"+m+" READ IO is "+rMark.getBwMbSec()+" MB/s    "
                        + "(MBread "+mbRead+" in "+sec+" sec)");
                App.updateMetrics(rMark);
                publish(rMark);
                
                run.setRunMax(rMark.getCumMax());
                run.setRunMin(rMark.getCumMin());
                run.setRunAvg(rMark.getCumAvg());
                run.setEndTime(new Date());
            }
            
            EntityManager em = EM.getEntityManager();
            em.getTransaction().begin();
            em.persist(run);
            em.getTransaction().commit();
            
            Gui.runPanel.addRun(run);
        }
        App.nextMarkNumber += App.numOfMarks;      
        return true;
    }
    
    @Override
    protected void process(List<DiskMark> markList) {
        markList.stream().forEach((m) -> {
            if (m.type==DiskMark.MarkType.WRITE) {
                Gui.addWriteMark(m);
            } else {
                Gui.addReadMark(m);
            }
        });
    }
    
    @Override
    protected void done() {
        if (App.autoRemoveData) {
            Util.deleteDirectory(App.dataDir);
        }
        App.state = App.State.IDLE_STATE;
        Gui.mainFrame.adjustSensitivity();
    }
}
