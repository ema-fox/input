let yt_id = null;
let cumms = 0;
let play_start = null;

let interval_id = null;

function take_time() {
    if (play_start) {
        cumms += (+(new Date()) - play_start);
        play_start = +(new Date());
    }
    if (cumms > 1000 * 60) {
        let res = cumms;
        cumms = 0;
        return res;
    } else {
        return 0;
    }
}

function send_watch_time(yt_id, ended, seconds) {
    let data = new URLSearchParams();
    data.append('yt-id', yt_id);
    data.append('ended', ended);
    data.append('seconds', seconds);
    navigator.sendBeacon('/add-watch-time', data);
}

addEventListener('message', evt => {
    if (evt.origin === "https://www.youtube.com") {
        if (interval_id) {
            clearInterval(interval_id);
            interval_id = null;
            console.log('first message from youtube received');
        }
        let info = JSON.parse(evt.data).info;
        if (info && info.playerState !== undefined) {
            yt_id = info.videoData.video_id;
            let ps = info.playerState;
            if (ps === 1) {
                play_start = +(new Date());
            } else {
                if (play_start) {
                    cumms += (+(new Date()) - play_start);
                    play_start = null;
                }
                if (ps === 0) {
                    send_watch_time(yt_id, true, Math.floor(cumms / 1000));
                }
            }
        }
    }
});

document.addEventListener("visibilitychange", () => {
  if (document.hidden) {
      let ms = take_time();
      if (ms) {
          send_watch_time(yt_id, false, Math.floor(ms / 1000));
      }
  }
});

addEventListener('DOMContentLoaded', () => {
    interval_id = setInterval(() => {
        console.log('trying to listen to youtube iframe');
        document.querySelector('.yt-player')
            .contentWindow.postMessage(JSON.stringify({
                event: "listening"
            }), '*');
    }, 100);
});
