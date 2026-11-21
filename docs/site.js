const roomImage = document.querySelector("#room-img")
const roomCaption = document.querySelector("#room-caption")
const roomButtons = [...document.querySelectorAll(".room-nav button")]
for (const button of roomButtons) {
  button.addEventListener("click", () => {
    for (const other of roomButtons)
      other.setAttribute("aria-pressed", String(button === other))
    roomImage.src = button.dataset.source
    roomImage.alt = roomCaption.textContent = button.dataset.caption
  })
}
const modal = document.querySelector("#film")
const video = modal.querySelector("video")
const trigger = document.querySelector("[data-film]")
if (new URLSearchParams(location.search).has("watch") && modal.showModal) {
  modal.showModal()
}
trigger.addEventListener("click", (event) => {
  if (!modal.showModal) return
  event.preventDefault()
  modal.showModal()
  video.play().catch(() => {})
})
document
  .querySelector("#close-film")
  .addEventListener("click", () => modal.close())
modal.addEventListener("click", (event) => {
  if (
    event.target === modal &&
    (event.offsetX < 0 ||
      event.offsetY < 0 ||
      event.offsetX > modal.clientWidth ||
      event.offsetY > modal.clientHeight)
  )
    modal.close()
})
modal.addEventListener("close", () => {
  video.pause()
  trigger.focus()
})
